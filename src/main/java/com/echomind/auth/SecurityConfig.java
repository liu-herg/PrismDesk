package com.echomind.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接口权限。无状态：不用 Session、不用 Cookie，每个请求带 Authorization: Bearer 访问令牌。
 * 因为不用 Cookie，也就不存在 CSRF 问题，CSRF 防护关闭。
 *
 * <p>路径级的规则在这里；"客户只能看自己的会话"这类和数据相关的规则在业务代码里。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE（流式对话、实时通知）结束时 Servlet 会做一次 ASYNC 分发，那时请求已经鉴权过了
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // 页面和静态资源
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/auth/login", "/auth/register", "/auth/refresh").permitAll()
                        // 健康检查、Prometheus 抓取、接口文档
                        .requestMatchers("/health", "/metrics", "/actuator/**", "/docs", "/v3/api-docs/**").permitAll()
                        // 只有客户能发消息；坐席和管理员只看记录
                        .requestMatchers("/chat", "/chat/stream").hasRole("CUSTOMER")
                        .requestMatchers("/tickets/**", "/trace/**").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/search").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/knowledge/stats").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers("/knowledge/**", "/monitor", "/skills/**", "/eval/**", "/admin/**").hasRole("ADMIN")
                        // 会话、通知、/auth/me、/auth/logout：登录即可，数据范围在业务代码里按角色控制
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()));
        return http.build();
    }

    /** 令牌里的 role 声明（CUSTOMER / AGENT / ADMIN）转成 Spring Security 的 ROLE_xxx 权限。 */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private AuthenticationEntryPoint unauthorized() {
        return (request, response, e) -> write(response, HttpServletResponse.SC_UNAUTHORIZED,
                request.getHeader("Authorization") == null ? "请先登录" : "登录已过期，请重新登录", request.getRequestURI());
    }

    private AccessDeniedHandler forbidden() {
        return (request, response, e) -> write(response, HttpServletResponse.SC_FORBIDDEN,
                "当前账号没有权限访问这个功能", request.getRequestURI());
    }

    /** 和 Spring Boot 默认错误响应的格式保持一致，前端统一读 message。 */
    private void write(HttpServletResponse response, int status, String message, String path) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", status == 401 ? "Unauthorized" : "Forbidden");
        body.put("message", message);
        body.put("path", path);
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
