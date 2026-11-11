package com.echomind.ticket;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "工单操作请求")
public record TicketActionRequest(
        @Schema(description = "已废弃：操作人一律取当前登录的坐席，这个字段会被忽略", example = "agent-01", deprecated = true)
        String operator,
        @Schema(description = "调用方看到的版本号。传了就要求和数据库一致，用来防止基于过期页面做操作", example = "0")
        @JsonProperty("version")
        Integer version,
        @Schema(description = "备注，会写入操作记录", example = "已联系用户，退款已提交")
        String note
) {
}
