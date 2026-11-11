package com.echomind.ticket;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.echomind.store.StoreConfig;
import com.echomind.store.TicketEntity;
import com.echomind.store.TicketEventEntity;
import com.echomind.store.TicketEventMapper;
import com.echomind.store.TicketMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多个坐席同时认领同一张工单，对比"先查后改"和"乐观锁"两种写法。需要真实的 MySQL：
 * <pre>
 * docker run --rm --network prismdesk_default -e IT_MYSQL_URL=jdbc:mysql://mysql:3306/prismdesk ... mvn test
 * </pre>
 * 只启动数据源、Flyway、MyBatis-Plus 和事务，不启动整个应用（不需要 Redis、LLM）。
 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@SpringBootTest(classes = {TicketService.class, StoreConfig.class})
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
class TicketConcurrencyTest {

    private static final int AGENTS = 10;
    private static final String CONV_PREFIX = "it-ticket-";

    @Autowired
    private TicketService ticketService;
    @Autowired
    private TicketMapper ticketMapper;
    @Autowired
    private TicketEventMapper eventMapper;

    private final List<Long> createdTicketIds = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = System.getenv("IT_MYSQL_URL");
        String separator = url.contains("?") ? "&" : "?";
        registry.add("spring.datasource.url", () -> url + separator
                + "useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("IT_MYSQL_USER", "prismdesk"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("IT_MYSQL_PASSWORD", "prismdesk123"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    }

    @AfterEach
    void cleanUp() {
        if (createdTicketIds.isEmpty()) {
            return;
        }
        eventMapper.delete(new QueryWrapper<TicketEventEntity>().in("ticket_id", createdTicketIds));
        ticketMapper.deleteByIds(createdTicketIds);
        createdTicketIds.clear();
    }

    @Test
    void naiveReadThenUpdateLetsSeveralAgentsClaimTheSameTicket() throws Exception {
        long ticketId = newTicket();
        // 所有线程都先读完、确认是"待分配"，再一起写——这是"先查后改"在并发下的最坏交错，用屏障固定下来
        CyclicBarrier allHaveRead = new CyclicBarrier(AGENTS);
        AtomicInteger believedTheyWon = new AtomicInteger();

        runConcurrently(agent -> {
            TicketEntity seen = ticketMapper.selectById(ticketId);
            allHaveRead.await(10, TimeUnit.SECONDS);
            if (TicketStatus.PENDING.name().equals(seen.getStatus())) {
                seen.setStatus(TicketStatus.IN_PROGRESS.name());
                seen.setAssignee(agent);
                // 普通的 updateById：WHERE id = ?，不带任何条件
                if (ticketMapper.updateById(seen) == 1) {
                    believedTheyWon.incrementAndGet();
                }
            }
        });

        System.out.printf("[先查后改] %d 个坐席同时认领，%d 个都以为自己抢到了，最终处理人：%s%n",
                AGENTS, believedTheyWon.get(), ticketMapper.selectById(ticketId).getAssignee());
        assertThat(believedTheyWon.get()).as("先查后改时会有多个坐席都认为自己认领成功").isGreaterThan(1);
    }

    @Test
    void optimisticLockLetsExactlyOneAgentClaim() throws Exception {
        long ticketId = newTicket();
        Set<String> winners = ConcurrentHashMap.newKeySet();
        AtomicInteger conflicts = new AtomicInteger();

        runConcurrently(agent -> {
            try {
                ticketService.transition(ticketId, TicketAction.CLAIM, agent, null, null);
                winners.add(agent);
            } catch (TicketConflictException expected) {
                conflicts.incrementAndGet();
            }
        });

        TicketEntity finalState = ticketMapper.selectById(ticketId);
        long claimEvents = eventMapper.selectByTicket(ticketId).stream()
                .filter(e -> "CLAIM".equals(e.getAction()))
                .count();
        System.out.printf("[乐观锁] %d 个坐席同时认领：成功 %d 个，冲突 %d 个，最终处理人：%s，认领记录 %d 条%n",
                AGENTS, winners.size(), conflicts.get(), finalState.getAssignee(), claimEvents);

        assertThat(winners).hasSize(1);
        assertThat(conflicts.get()).isEqualTo(AGENTS - 1);
        assertThat(finalState.getAssignee()).isEqualTo(winners.iterator().next());
        assertThat(finalState.getVersion()).isEqualTo(1);
        assertThat(claimEvents).as("操作记录和状态在同一个事务里，只有成功的那次会留下记录").isEqualTo(1);
    }

    @Test
    void concurrentEscalationsOfOneConversationOpenOnlyOneTicket() throws Exception {
        String conversationId = CONV_PREFIX + UUID.randomUUID();
        Set<Long> returnedIds = ConcurrentHashMap.newKeySet();
        AtomicInteger created = new AtomicInteger();

        runConcurrently(agent -> {
            TicketService.EscalationResult result = ticketService.openForEscalation(escalation(conversationId));
            returnedIds.add(result.ticket().getId());
            createdTicketIds.add(result.ticket().getId());
            if (result.created()) {
                created.incrementAndGet();
            }
        });

        long rows = ticketMapper.selectCount(new QueryWrapper<TicketEntity>().eq("conversation_id", conversationId));
        System.out.printf("[重复建单] 同一会话 %d 个请求同时转人工：新建 %d 张，库里 %d 张%n", AGENTS, created.get(), rows);
        assertThat(created.get()).isEqualTo(1);
        assertThat(rows).isEqualTo(1);
        assertThat(returnedIds).as("所有请求拿到的都是同一张工单").hasSize(1);
    }

    @Test
    void fullLifecycleWritesOneEventPerStep() {
        long ticketId = newTicket();
        ticketService.transition(ticketId, TicketAction.CLAIM, "agent-a", 0, null);
        ticketService.transition(ticketId, TicketAction.RESOLVE, "agent-a", 1, "已退款");
        ticketService.transition(ticketId, TicketAction.REOPEN, "agent-a", 2, "用户说没收到");
        ticketService.transition(ticketId, TicketAction.RESOLVE, "agent-a", 3, "已补发");
        TicketEntity closed = ticketService.transition(ticketId, TicketAction.CLOSE, "agent-a", 4, null);

        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(eventMapper.selectByTicket(ticketId)).extracting(TicketEventEntity::getAction)
                .containsExactly("CREATE", "CLAIM", "RESOLVE", "REOPEN", "RESOLVE", "CLOSE");
        // 关闭后不再算未结，同一会话可以再建新单
        assertThat(ticketMapper.selectOpenByConversation(closed.getConversationId())).isNull();
    }

    private long newTicket() {
        TicketService.EscalationResult result = ticketService.openForEscalation(escalation(CONV_PREFIX + UUID.randomUUID()));
        createdTicketIds.add(result.ticket().getId());
        return result.ticket().getId();
    }

    private static Escalation escalation(String conversationId) {
        return new Escalation(conversationId, "it-user", "HIGH", "refund", "并发测试", "用户：退款\nAI：已为您转人工", "it");
    }

    private void runConcurrently(AgentTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(AGENTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < AGENTS; i++) {
                String agent = "agent-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        task.run(agent);
                    } catch (Throwable error) {
                        unexpected.add(error);
                    }
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(unexpected).as("除了预期的冲突，不应有其他异常").isEmpty();
    }

    @FunctionalInterface
    private interface AgentTask {
        void run(String agent) throws Exception;
    }
}
