package com.echomind.memory;

import com.echomind.config.EchoMindProperties;
import com.echomind.llm.LlmGateway;
import com.echomind.lock.ConversationLock;
import com.echomind.store.EpisodicMemoryEntity;
import com.echomind.store.EpisodicMemoryMapper;
import com.echomind.store.UserProfileEntity;
import com.echomind.store.UserProfileMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final EchoMindProperties properties;
    private final LlmGateway llmGateway;
    private final EpisodicMemoryMapper episodicMapper;
    private final UserProfileMapper profileMapper;
    private final ConversationLock conversationLock;

    /** 相似度排序时，最多取该用户最近多少条情景记忆作为候选。 */
    private static final int EPISODIC_CANDIDATES = 200;

    public MemoryManager(StringRedisTemplate redisTemplate,
                         ObjectMapper objectMapper,
                         EchoMindProperties properties,
                         LlmGateway llmGateway,
                         EpisodicMemoryMapper episodicMapper,
                         UserProfileMapper profileMapper,
                         ConversationLock conversationLock) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.llmGateway = llmGateway;
        this.episodicMapper = episodicMapper;
        this.profileMapper = profileMapper;
        this.conversationLock = conversationLock;
    }

    public MemoryContext getContext(String userId, String conversationId, String query) {
        List<ConversationMessage> recent = getWorkingMemory(userId, conversationId);
        List<String> relevantHistory = searchEpisodic(userId, query);
        Map<String, Object> profile = loadProfile(userId);
        String summary = safeRedisGet(summaryKey(userId, conversationId));
        return new MemoryContext(recent, relevantHistory, profile, summary == null ? "" : summary);
    }

    /**
     * "写入 → 判断长度 → 压缩"必须作为一个整体执行。
     * 压缩会先读出快照、调用 LLM 做摘要、再删除整个列表并写回最近几条；
     * 如果这期间同一会话又写入了新消息，新消息不在快照里，会被一起删掉。
     * 所以这里按会话加锁，保证同一会话的写入串行执行。
     */
    public void addMessage(String userId, String conversationId, MessageRole role, String content) {
        ConversationMessage message = new ConversationMessage(role, safe(content), Instant.now(), Map.of());
        try {
            conversationLock.run(userId, conversationId, () -> appendAndMaybeCompress(userId, conversationId, message));
        } catch (Exception ex) {
            log.warn("Failed to write working memory: {}", ex.getMessage());
        }
    }

    private void appendAndMaybeCompress(String userId, String conversationId, ConversationMessage message) {
        String key = wmKey(userId, conversationId);
        try {
            redisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(message));
            redisTemplate.expire(key, Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size >= properties.getMemory().getCompressAt()) {
                compress(userId, conversationId);
            }
        } catch (Exception ex) {
            log.warn("Failed to write working memory: {}", ex.getMessage());
        }
    }

    @Async("workerExecutor")
    public void updateProfile(String userId, String conversationId) {
        List<ConversationMessage> messages = getWorkingMemory(userId, conversationId);
        if (messages.isEmpty()) {
            return;
        }
        String text = tail(messages, 10).stream()
                .map(m -> m.role().name().toLowerCase() + ": " + m.content())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        String prompt = """
                从以下客服对话中提炼用户画像，返回 JSON：
                %s
                格式: {"preferences":["..."],"entities":{"产品":[],"问题类型":[]}}
                """.formatted(text);
        try {
            String raw = llmGateway.chat("", prompt, 0.0, 512);
            Map<String, Object> profile = objectMapper.readValue(sliceJson(raw), new TypeReference<>() {
            });
            UserProfileEntity entity = new UserProfileEntity();
            entity.setUserId(userId);
            entity.setProfile(objectMapper.writeValueAsString(profile));
            entity.setUpdatedAt(LocalDateTime.now());
            profileMapper.upsert(entity);
        } catch (Exception ex) {
            log.warn("Profile update failed: {}", ex.getMessage());
        }
    }

    public List<ConversationMessage> getWorkingMemory(String userId, String conversationId) {
        try {
            String key = wmKey(userId, conversationId);
            List<String> raw = redisTemplate.opsForList().range(key, 0, properties.getMemory().getWorkingMax() - 1);
            if (raw == null) {
                return List.of();
            }
            List<ConversationMessage> messages = new ArrayList<>();
            for (int i = raw.size() - 1; i >= 0; i--) {
                messages.add(objectMapper.readValue(raw.get(i), ConversationMessage.class));
            }
            return messages;
        } catch (Exception ex) {
            log.warn("Failed to read working memory: {}", ex.getMessage());
            return List.of();
        }
    }

    private void compress(String userId, String conversationId) {
        List<ConversationMessage> messages = getWorkingMemory(userId, conversationId);
        if (messages.size() < properties.getMemory().getCompressAt()) {
            return;
        }
        int keep = Math.min(5, messages.size());
        List<ConversationMessage> oldMessages = messages.subList(0, messages.size() - keep);
        List<ConversationMessage> keepMessages = messages.subList(messages.size() - keep, messages.size());
        String text = oldMessages.stream()
                .map(m -> m.role().name().toLowerCase() + ": " + m.content())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        String summary;
        try {
            summary = llmGateway.chat("", "用 2-3 句话总结以下对话关键信息：\n" + text, 0.0, 256);
        } catch (Exception ex) {
            summary = "对话包含 " + oldMessages.size() + " 条历史消息。";
        }
        try {
            EpisodicMemoryEntity entry = new EpisodicMemoryEntity();
            entry.setUserId(safe(userId));
            entry.setConversationId(safe(conversationId));
            entry.setSummary(summary);
            entry.setFullText(text);
            entry.setCreatedAt(LocalDateTime.now());
            episodicMapper.insert(entry);
        } catch (Exception ex) {
            // 写不进长期记忆就不要清理工作记忆，否则这段对话会彻底丢失
            log.warn("Failed to save episodic memory, keep working memory untouched: {}", ex.getMessage());
            return;
        }
        String key = wmKey(userId, conversationId);
        try {
            String oldSummary = safeRedisGet(summaryKey(userId, conversationId));
            redisTemplate.opsForValue().set(summaryKey(userId, conversationId), ((oldSummary == null ? "" : oldSummary + "\n") + summary).trim(),
                    Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
            redisTemplate.delete(key);
            for (int i = keepMessages.size() - 1; i >= 0; i--) {
                redisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(keepMessages.get(i)));
            }
            redisTemplate.expire(key, Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
        } catch (Exception ex) {
            log.warn("Memory compression failed: {}", ex.getMessage());
        }
    }

    private List<String> searchEpisodic(String userId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<EpisodicMemoryEntity> candidates;
        try {
            candidates = episodicMapper.selectRecent(safe(userId), EPISODIC_CANDIDATES);
        } catch (Exception ex) {
            log.warn("Failed to load episodic memory: {}", ex.getMessage());
            return List.of();
        }
        double[] queryVec = embed(query);
        return candidates.stream()
                .map(e -> new ScoredSummary(e.getSummary(), cosine(queryVec, embed(e.getSummary()))))
                .sorted(Comparator.comparingDouble(ScoredSummary::score).reversed())
                .limit(5)
                .map(ScoredSummary::summary)
                .toList();
    }

    private Map<String, Object> loadProfile(String userId) {
        try {
            UserProfileEntity entity = profileMapper.selectById(safe(userId));
            if (entity == null || entity.getProfile() == null) {
                return Map.of();
            }
            return objectMapper.readValue(entity.getProfile(), new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("Failed to load user profile: {}", ex.getMessage());
            return Map.of();
        }
    }

    private String safeRedisGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<ConversationMessage> tail(List<ConversationMessage> messages, int n) {
        return messages.subList(Math.max(0, messages.size() - n), messages.size());
    }

    private String sliceJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : "{}";
    }

    private String wmKey(String userId, String conversationId) {
        return "wm:" + safe(userId) + ":" + safe(conversationId);
    }

    private String summaryKey(String userId, String conversationId) {
        return "summary:" + safe(userId) + ":" + safe(conversationId);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private double[] embed(String text) {
        double[] vector = new double[256];
        String normalized = text == null ? "" : text.toLowerCase();
        for (int n = 1; n <= 3; n++) {
            for (int i = 0; i + n <= normalized.length(); i++) {
                String gram = normalized.substring(i, i + n);
                int hash = gram.hashCode();
                int idx = Math.floorMod(hash, vector.length);
                vector[idx] += (hash & 1) == 0 ? 1.0 : -1.0;
            }
        }
        return vector;
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record ScoredSummary(String summary, double score) {
    }
}
