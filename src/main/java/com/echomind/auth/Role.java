package com.echomind.auth;

public enum Role {
    /** 客户：只能和客服对话、看自己的会话 */
    CUSTOMER,
    /** 坐席：处理工单、查看所有会话记录 */
    AGENT,
    /** 管理员：坐席的权限 + 知识库、运行状态、账号管理 */
    ADMIN;

    public boolean isStaff() {
        return this == AGENT || this == ADMIN;
    }
}
