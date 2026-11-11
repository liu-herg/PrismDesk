package com.echomind.auth;

import com.echomind.conversation.ConversationController;
import com.echomind.conversation.ConversationService;
import com.echomind.notify.TicketNotice;
import com.echomind.notify.TicketNotifier;
import com.echomind.store.TicketEntity;
import com.echomind.ticket.TicketAction;
import com.echomind.ticket.TicketController;
import com.echomind.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口权限：谁能访问什么，以及"身份只认令牌、不认请求参数"。
 * 不需要数据库和 Redis：业务服务全部 mock，令牌用 spring-security-test 直接构造。
 */
@WebMvcTest(controllers = {TicketController.class, ConversationController.class, AdminUserController.class, AuthController.class})
@Import(SecurityConfig.class)
class SecurityRulesTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private TicketService ticketService;
    @MockitoBean
    private TicketNotifier ticketNotifier;
    @MockitoBean
    private ConversationService conversationService;
    @MockitoBean
    private AuthService authService;

    private static JwtRequestPostProcessor as(String username, Role role) {
        return jwt().jwt(j -> j.subject(username).claim("role", role.name()).claim("name", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Test
    void anonymousRequestsGet401WithChineseMessage() throws Exception {
        mvc.perform(get("/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录"));
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginEndpointIsPublic() throws Exception {
        when(authService.login("u1001", "demo123")).thenReturn(new AuthService.LoginResult("a", 1L, "r", 60L,
                new UserView(1L, "u1001", "张敏", "CUSTOMER", true, LocalDateTime.now(), null)));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1001\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("a"))
                .andExpect(jsonPath("$.user.display_name").value("张敏"));
    }

    @Test
    void customersCannotSeeTicketsAndStaffCannotChat() throws Exception {
        mvc.perform(get("/tickets").with(as("u1001", Role.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("当前账号没有权限访问这个功能"));
        when(ticketService.list(any(), any(), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/tickets").with(as("agent-01", Role.AGENT))).andExpect(status().isOk());
        // 坐席不能以客户身份发消息（被权限规则拦下，到不了控制器）
        mvc.perform(post("/chat").with(as("agent-01", Role.AGENT))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyAdminsManageAccounts() throws Exception {
        when(authService.listUsers()).thenReturn(List.of());
        mvc.perform(get("/admin/users").with(as("agent-01", Role.AGENT))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users").with(as("admin", Role.ADMIN))).andExpect(status().isOk());
    }

    @Test
    void ticketOperatorComesFromTokenNotFromRequestBody() throws Exception {
        TicketEntity updated = new TicketEntity();
        updated.setId(5L);
        updated.setStatus("IN_PROGRESS");
        updated.setAssignee("agent-01");
        updated.setUserId("u1001");
        updated.setVersion(1);
        when(ticketService.transition(eq(5L), eq(TicketAction.CLAIM), eq("agent-01"), eq(0), isNull()))
                .thenReturn(updated);

        // 请求体里冒充 agent-02，实际以令牌里的 agent-01 执行
        mvc.perform(post("/tickets/5/claim").with(as("agent-01", Role.AGENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"agent-02\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").value("agent-01"));
        verify(ticketService, never()).transition(anyLong(), any(), eq("agent-02"), any(), any());
        verify(ticketNotifier).publish(any(TicketNotice.class));
    }

    @Test
    void customersOnlySeeTheirOwnConversationsWhateverTheyAskFor() throws Exception {
        when(conversationService.listConversations(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.listMessages(any(), any(), any(), anyInt()))
                .thenReturn(new ConversationService.MessagePage(List.of(), null));
        when(conversationService.listMessagesForStaff(any(), any(), anyInt()))
                .thenReturn(new ConversationService.MessagePage(List.of(), null));

        // 客户传了别人的 user_id：被忽略，仍然只查自己的
        mvc.perform(get("/conversations").param("user_id", "u1002").with(as("u1001", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("u1001"));
        verify(conversationService).listConversations(eq("u1001"), any(), anyInt());

        // 坐席可以指定看哪个客户
        mvc.perform(get("/conversations").param("user_id", "u1002").with(as("agent-01", Role.AGENT)))
                .andExpect(jsonPath("$.user_id").value("u1002"));

        // 查消息：客户走带归属校验的方法，坐席走不校验归属的方法
        mvc.perform(get("/conversations/c-1/messages").with(as("u1001", Role.CUSTOMER))).andExpect(status().isOk());
        verify(conversationService).listMessages(eq("u1001"), eq("c-1"), any(), anyInt());
        mvc.perform(get("/conversations/c-1/messages").with(as("agent-01", Role.AGENT))).andExpect(status().isOk());
        verify(conversationService).listMessagesForStaff(eq("c-1"), any(), anyInt());
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
