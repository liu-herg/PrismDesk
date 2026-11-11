package com.echomind.conversation;

import com.echomind.store.ChatMessageEntity;
import com.echomind.store.ConversationEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "会话历史", description = "查询 MySQL 中保存的完整会话和消息")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/conversations")
    @Operation(summary = "会话列表", description = "按最后活跃时间倒序；把上一页最后一条的 last_message_at 作为 before 传入即可翻页。")
    public Map<String, Object> conversations(
            @RequestParam("user_id") String userId,
            @RequestParam(value = "before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        List<Map<String, Object>> items = conversationService.listConversations(userId, before, size).stream()
                .map(this::toView)
                .toList();
        return Map.of("items", items);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "会话消息", description = "游标分页，按时间正序返回；把 next_before_id 作为 before_id 传入即可加载更早的消息。")
    public Map<String, Object> messages(
            @PathVariable String conversationId,
            @RequestParam("user_id") String userId,
            @RequestParam(value = "before_id", required = false) Long beforeId,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        ConversationService.MessagePage page = conversationService.listMessages(userId, conversationId, beforeId, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.items().stream().map(this::toView).toList());
        body.put("next_before_id", page.nextBeforeId());
        return body;
    }

    private Map<String, Object> toView(ConversationEntity c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("conv_id", c.getId());
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
        return view;
    }
}
