package com.tonyhe.fusion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonyhe.fusion.common.CommonResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Enforces business-resource ownership before Spring serves a file from /media/**. */
@Component
@RequiredArgsConstructor
public class MediaAccessFilter extends OncePerRequestFilter {

    private final MediaAccessService mediaAccessService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/media/")
                || uri.startsWith("/media/workbench-images/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已过期");
            return;
        }
        String mediaUrl = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
        if (!mediaAccessService.canRead(mediaUrl, userId)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "无权访问该媒体资源");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(CommonResult.error(status, message)));
    }
}
