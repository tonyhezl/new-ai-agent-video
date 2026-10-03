package com.tonyhe.fusion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MediaAccessFilterTests {

    private final MediaAccessService mediaAccessService = mock(MediaAccessService.class);
    private final MediaAccessFilter filter = new MediaAccessFilter(mediaAccessService, new ObjectMapper());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticatedMediaRequestReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/media/images/a.png");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("未登录");
    }

    @Test
    void inaccessibleMediaRequestReturns403() throws Exception {
        authenticate(7L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/media/images/a.png");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(mediaAccessService.canRead("/media/images/a.png", 7L)).thenReturn(false);

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("无权访问");
    }

    @Test
    void accessibleMediaRequestContinuesToStaticResourceHandler() throws Exception {
        authenticate(7L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/media/images/a.png");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(mediaAccessService.canRead("/media/images/a.png", 7L)).thenReturn(true);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private void authenticate(Long userId) {
        SecurityUserDetails details = new SecurityUserDetails(userId, "user", "", 1, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }
}
