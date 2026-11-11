package com.echomind.ticket;

import com.echomind.store.TicketEntity;
import com.echomind.store.TicketEventEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@Tag(name = "工单", description = "转人工工单：认领、退回、解决、关闭、重新打开")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping("/tickets")
    @Operation(summary = "工单列表", description = "按 id 倒序；把上一页最后一条的 id 作为 before_id 传入即可翻页。")
    public Map<String, Object> list(
            @Parameter(description = "pending / in_progress / resolved / closed") @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "assignee", required = false) String assignee,
            @RequestParam(value = "before_id", required = false) Long beforeId,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        List<TicketEntity> tickets = ticketService.list(parseStatus(status), assignee, beforeId, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", tickets.stream().map(TicketController::view).toList());
        body.put("next_before_id", tickets.size() < Math.max(1, Math.min(size, TicketService.MAX_PAGE_SIZE))
                ? null : tickets.get(tickets.size() - 1).getId());
        return body;
    }

    @GetMapping("/tickets/{id}")
    @Operation(summary = "工单详情", description = "包含完整的操作记录。")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> body = view(ticketService.get(id));
        body.put("events", ticketService.events(id).stream().map(TicketController::view).toList());
        return body;
    }

    @PostMapping("/tickets/{id}/{action}")
    @Operation(summary = "工单操作", description = "action 取值：claim（认领）、release（退回）、resolve（解决）、close（关闭）、reopen（重新打开）。"
            + "状态不允许或并发冲突返回 409，非处理人执行 release/resolve 返回 403。")
    public Map<String, Object> act(@PathVariable long id,
                                   @PathVariable String action,
                                   @Valid @RequestBody TicketActionRequest request) {
        TicketAction parsed;
        try {
            parsed = TicketAction.parse(action);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        return view(ticketService.transition(id, parsed, request.operator(), request.version(), request.note()));
    }

    private static TicketStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TicketStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的工单状态: " + status);
        }
    }

    static Map<String, Object> view(TicketEntity t) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", t.getId());
        view.put("status", t.getStatus() == null ? null : t.getStatus().toLowerCase(Locale.ROOT));
        view.put("priority", t.getPriority());
        view.put("conv_id", t.getConversationId());
        view.put("user_id", t.getUserId());
        view.put("intent", t.getIntent());
        view.put("reason", t.getReason());
        view.put("summary", t.getSummary());
        view.put("assignee", t.getAssignee());
        view.put("version", t.getVersion());
        view.put("created_at", t.getCreatedAt());
        view.put("assigned_at", t.getAssignedAt());
        view.put("resolved_at", t.getResolvedAt());
        view.put("closed_at", t.getClosedAt());
        return view;
    }

    private static Map<String, Object> view(TicketEventEntity e) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("action", e.getAction().toLowerCase(Locale.ROOT));
        view.put("from", e.getFromStatus() == null ? null : e.getFromStatus().toLowerCase(Locale.ROOT));
        view.put("to", e.getToStatus().toLowerCase(Locale.ROOT));
        view.put("operator", e.getOperator());
        view.put("note", e.getNote());
        view.put("at", e.getCreatedAt());
        return view;
    }
}
