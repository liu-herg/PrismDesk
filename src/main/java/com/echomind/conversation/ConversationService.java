package com.echomind.conversation;

import com.echomind.memory.MessageRole;
import com.echomind.store.ChatMessageEntity;
import com.echomind.store.ChatMessageMapper;
import com.echomind.store.ConversationEntity;
import com.echomind.store.ConversationMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话与消息的持久化。Redis 里的工作记忆只保留最近几条并且会过期，
 * 这里是完整对话历史的权威存储。
 */
@Service
public class ConversationService {

    static final int MAX_PAGE_SIZE = 100;
    private static final int TITLE_MAX = 50;

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;

    public ConversationService(ConversationMapper conversationMapper, ChatMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * 会话已存在且属于别的用户时拒绝，防止拿别人的 conv_id 读写对方的记忆。
     * 注意：在接入登录鉴权之前，user_id 仍由客户端传入。
     */
    public void assertWritable(String userId, String conversationId) {
        ConversationEntity existing = conversationMapper.selectById(conversationId);
        if (existing != null && !existing.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "会话不属于当前用户");
        }
    }

    /** 一轮对话的会话计数和两条消息在同一个事务里提交，要么都写入，要么都不写。 */
    @Transactional
    public void recordTurn(ChatTurn turn) {
        LocalDateTime now = LocalDateTime.now();
        conversationMapper.upsertTurn(turn.conversationId(), turn.userId(), title(turn.userMessage()), 2, now);
        messageMapper.insert(message(turn, MessageRole.USER, turn.userMessage(), now));
        // 回答的时间戳晚 1ms，保证同一轮里"先问后答"的顺序在按时间排序时也成立
        messageMapper.insert(message(turn, MessageRole.ASSISTANT, turn.assistantMessage(), now.plusNanos(1_000_000)));
    }

    public List<ConversationEntity> listConversations(String userId, LocalDateTime before, int size) {
        return conversationMapper.pageByUser(userId, before, clamp(size));
    }

    /**
     * 返回一页消息，按时间正序排列（便于前端直接渲染）。
     * nextBeforeId 是本页最早一条的 id，传回来就能继续往前翻；为 null 表示没有更早的消息了。
     */
    public MessagePage listMessages(String userId, String conversationId, Long beforeId, int size) {
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            // 不区分"不存在"和"不属于你"，避免泄露别人的会话是否存在
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        int pageSize = clamp(size);
        // 多查一条，用来判断是否还有下一页
        List<ChatMessageEntity> rows = messageMapper.pageBefore(conversationId, beforeId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<ChatMessageEntity> page = new ArrayList<>(hasMore ? rows.subList(0, pageSize) : rows);
        Long nextBeforeId = hasMore ? page.get(page.size() - 1).getId() : null;
        Collections.reverse(page);
        return new MessagePage(page, nextBeforeId);
    }

    private ChatMessageEntity message(ChatTurn turn, MessageRole role, String content, LocalDateTime at) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setConversationId(turn.conversationId());
        entity.setUserId(turn.userId());
        entity.setRole(role.name());
        entity.setContent(content == null ? "" : content);
        entity.setRequestId(turn.requestId());
        entity.setCreatedAt(at);
        if (role == MessageRole.ASSISTANT) {
            entity.setIntent(turn.intent());
            entity.setAgentType(turn.agentType());
            entity.setEscalated(turn.escalated());
            entity.setLatencyMs((int) Math.min(Integer.MAX_VALUE, turn.latencyMs()));
        } else {
            entity.setEscalated(false);
        }
        return entity;
    }

    private String title(String firstMessage) {
        String text = firstMessage == null ? "" : firstMessage.strip().replaceAll("\\s+", " ");
        return text.length() <= TITLE_MAX ? text : text.substring(0, TITLE_MAX);
    }

    private int clamp(int size) {
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }

    public record MessagePage(List<ChatMessageEntity> items, Long nextBeforeId) {
    }
}
