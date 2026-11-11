package com.echomind.ticket;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "工单操作请求")
public record TicketActionRequest(
        @Schema(description = "操作人（坐席 ID）。接入登录鉴权之前由调用方传入", example = "agent-01")
        @NotBlank
        String operator,
        @Schema(description = "调用方看到的版本号。传了就要求和数据库一致，用来防止基于过期页面做操作", example = "0")
        @JsonProperty("version")
        Integer version,
        @Schema(description = "备注，会写入操作记录", example = "已联系用户，退款已提交")
        String note
) {
}
