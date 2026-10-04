package com.arkrag.server.security;

import com.arkrag.server.config.ArkRagServerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Bearer token 鉴权（HTTP API 与 MCP 端点共用）。
 * 放行清单：/api/v1/health（插件"测试连接"需要无鉴权探活）；
 * token 比较用 MessageDigest.isEqual（常量时间，防时序侧信道）。
 * 同时接受 X-Api-Key 头（部分 MCP 客户端不便自定义 Authorization）。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private static final List<String> EXEMPT = List.of("/api/v1/health", "/actuator/health");

    private final ArkRagServerProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthFilter(ArkRagServerProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (EXEMPT.contains(uri)) {
            return true;
        }
        // 管理控制台静态资源免鉴权（HTML/JS 无敏感数据；数据 API 仍全部鉴权）
        return uri.equals("/admin") || uri.startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String expected = props.getServer().getToken();
        String provided = bearer(request.getHeader("Authorization"));
        if (provided == null) {
            provided = request.getHeader("X-Api-Key");
        }
        if (provided == null || !constantTimeEquals(expected, provided)) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(mapper.writeValueAsString(Map.of("error",
                    Map.of("code", "UNAUTHORIZED",
                            "message", "缺少或错误的 API token（Authorization: Bearer <token> 或 X-Api-Key）"))));
            return;
        }
        chain.doFilter(request, response);
    }

    private static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String h = header.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String v = h.substring(7).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
