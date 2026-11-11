package com.echomind.memory;

import com.echomind.config.EchoMindProperties;
import com.echomind.llm.LlmGateway;
import com.echomind.lock.ConversationLock;
import com.echomind.lock.RedissonConversationLock;
import com.echomind.store.EpisodicMemoryEntity;
import com.echomind.store.EpisodicMemoryMapper;
import com.echomind.store.UserProfileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 并发写入同一个会话的工作记忆，对比"不加锁"和"加会话锁"两种情况。
 *
 * <p>检查的不变量：每条消息应当恰好出现一次——要么还在 Redis 工作记忆里，
 * 要么已被压缩归档到情景记忆（长期记忆）。丢了或者归档了多次，都算数据不一致。
 *
 * <p>需要真实的 Redis，设置环境变量 IT_REDIS_HOST 后才会运行，例如连接 docker compose 里的 Redis：
 * <pre>
 * docker run --rm --network prismdesk_default -e IT_REDIS_HOST=redis -e IT_REDIS_PASSWORD=echomind123 ...
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "IT_REDIS_HOST", matches = ".+")
class MemoryConcurrencyTest {

    private static final int THREADS = 8;
    private static final int MESSAGES_PER_THREAD = 40;
    private static final int TOTAL = THREADS * MESSAGES_PER_THREAD;
    /** 模拟压缩时调用 LLM 做摘要的耗时。真实环境是几秒，这里用 20ms 就足以暴露问题。 */
    private static final long SUMMARIZE_MILLIS = 20;
    private static final Pattern MESSAGE_ID = Pattern.compile("m-\\d+-\\d+");

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedissonClient redisson;

    @BeforeAll
    static void connect() {
        String host = System.getenv("IT_REDIS_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("IT_REDIS_PORT", "6379"));
        String password = System.getenv().getOrDefault("IT_REDIS_PASSWORD", "");

        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        if (!password.isBlank()) {
            standalone.setPassword(RedisPassword.of(password));
        }
        connectionFactory = new LettuceConnectionFactory(standalone);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);

        Config config = new Config();
        SingleServerConfig server = config.useSingleServer().setAddress("redis://" + host + ":" + port);
        if (!password.isBlank()) {
            server.setPassword(password);
        }
        redisson = Redisson.create(config);
    }

    @AfterAll
    static void disconnect() {
        if (redisson != null) {
            redisson.shutdown();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void withoutLockConcurrentWritesLoseOrDuplicateMessages() throws Exception {
        Outcome outcome = runConcurrentWrites(new NoLock());

        System.out.printf("[不加锁] 共写入 %d 条：丢失 %d 条，重复归档 %d 条%n", TOTAL, outcome.lost(), outcome.duplicated());
        // 这个用例的作用是把问题复现出来：不加锁时数据必然不一致
        assertThat(outcome.lost() + outcome.duplicated())
                .as("不加锁时应当出现丢失或重复")
                .isGreaterThan(0);
    }

    @Test
    void withConversationLockEveryMessageIsKeptExactlyOnce() throws Exception {
        Outcome outcome = runConcurrentWrites(new RedissonConversationLock(redisson, 60_000));

        System.out.printf("[加会话锁] 共写入 %d 条：丢失 %d 条，重复归档 %d 条%n", TOTAL, outcome.lost(), outcome.duplicated());
        assertThat(outcome.lost()).as("丢失的消息数").isZero();
        assertThat(outcome.duplicated()).as("重复归档的消息数").isZero();
    }

    private Outcome runConcurrentWrites(ConversationLock lock) throws Exception {
        String userId = "it-user-" + UUID.randomUUID();
        String conversationId = "it-conv";
        List<String> archivedTexts = new CopyOnWriteArrayList<>();

        EpisodicMemoryMapper episodicMapper = mock(EpisodicMemoryMapper.class);
        when(episodicMapper.insert(any(EpisodicMemoryEntity.class))).thenAnswer(invocation -> {
            EpisodicMemoryEntity entity = invocation.getArgument(0);
            archivedTexts.add(entity.getFullText());
            return 1;
        });
        LlmGateway slowSummarizer = (systemPrompt, userPrompt, temperature, maxTokens) -> {
            sleep(SUMMARIZE_MILLIS);
            return "摘要";
        };
        MemoryManager memoryManager = new MemoryManager(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                new EchoMindProperties(),
                slowSummarizer,
                episodicMapper,
                mock(UserProfileMapper.class),
                lock
        );

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        try {
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < MESSAGES_PER_THREAD; i++) {
                            memoryManager.addMessage(userId, conversationId, MessageRole.USER, "m-" + thread + "-" + i);
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS)).as("并发写入应在 120 秒内完成").isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 统计每条消息出现的次数：工作记忆里的 + 已归档到情景记忆里的
        Map<String, Integer> seen = new HashMap<>();
        List<String> all = new ArrayList<>(archivedTexts);
        memoryManager.getWorkingMemory(userId, conversationId).forEach(message -> all.add(message.content()));
        for (String text : all) {
            Matcher matcher = MESSAGE_ID.matcher(text == null ? "" : text);
            while (matcher.find()) {
                seen.merge(matcher.group(), 1, Integer::sum);
            }
        }
        Set<String> expected = new TreeSet<>();
        for (int t = 0; t < THREADS; t++) {
            for (int i = 0; i < MESSAGES_PER_THREAD; i++) {
                expected.add("m-" + t + "-" + i);
            }
        }
        long lost = expected.stream().filter(id -> !seen.containsKey(id)).count();
        long duplicated = seen.values().stream().filter(count -> count > 1).count();

        redisTemplate.delete(redisTemplate.keys("*" + userId + "*"));
        return new Outcome(lost, duplicated);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record Outcome(long lost, long duplicated) {
    }

    /** 不加锁的实现，用来复现问题。 */
    private static final class NoLock implements ConversationLock {
        @Override
        public <T> T execute(String userId, String conversationId, Supplier<T> action) {
            return action.get();
        }
    }
}
