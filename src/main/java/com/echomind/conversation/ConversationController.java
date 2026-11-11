package com.echomind.conversation;

import com.echomind.auth.CurrentUser;
import com.echomind.store.ChatMessageEntity;
import com.echomind.store.ConversationEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话历史。数据范围按角色：客户只能看自己的；坐席和管理员可以看任何客户的（处理工单时要看上下文）。
 * 客户传别人的 user_id 会被忽略，传别人的会话 ID 返回 404（和"不存在"一样，不泄露会话是否存在）。
 */
@RestController
@Tag(name = "会话历史", description = "查询 MySQL 中保存的完整会话和消息")
public class ConversationController {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    public ConversationController(ConversationService conversationService, ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/conversations")
    @Operation(summary = "会话列表", description = "按最后活跃时间倒序；把上一页最后一条的 last_message_at 作为 before 传入即可翻页。"
            + "客户只能看自己的；坐席和管理员用 user_id 指定看哪个客户。")
    public Map<String, Object> conversations(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "只对坐席和管理员有效") @RequestParam(value = "user_id", required = false) String userId,
            @RequestParam(value = "before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        CurrentUser user = CurrentUser.of(jwt);
        String owner = user.isStaff() && userId != null && !userId.isBlank() ? userId.trim() : user.username();
        List<Map<String, Object>> items = conversationService.listConversations(owner, before, size).stream()
                .map(this::toView)
                .toList();
        return Map.of("items", items, "user_id", owner);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "会话消息", description = "游标分页，按时间正序返回；把 next_before_id 作为 before_id 传入即可加载更早的消息。")
    public Map<String, Object> messages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @RequestParam(value = "before_id", required = false) Long beforeId,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        CurrentUser user = CurrentUser.of(jwt);
        ConversationService.MessagePage page = user.isStaff()
                ? conversationService.listMessagesForStaff(conversationId, beforeId, size)
                : conversationService.listMessages(user.username(), conversationId, beforeId, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.items().stream().map(this::toView).toList());
        body.put("next_before_id", page.nextBeforeId());
        return body;
    }

    private Map<String, Object> toView(ConversationEntity c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("conv_id", c.getId());
        view.put("user_id", c.getUserId());
        view.put("title", c.getTitle());
        view.put("message_count", c.getMessageCount());
        view.put("last_message_at", c.getLastMessageAt());
        view.put("created_at", c.getCreatedAt());
        return view;
    }

    private Map<String, Object> toView(ChatMessageEntity m) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", m.getId());
        view.put("role", m.getRole().toLowerCase());
        view.put("content", m.getContent());
        view.put("request_id", m.getRequestId());
        view.put("intent", m.getIntent());
        view.put("agent_type", m.getAgentType());
        view.put("escalated", m.getEscalated());
        view.put("latency_ms", m.getLatencyMs());
        view.put("created_at", m.getCreatedAt());
        view.put("trace", parseTrace(m.getTrace()));
        return view;
    }

    private Map<String, Object> parseTrace(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP);
        } catch (Exception e) {
            return null;
        }
    }
}
