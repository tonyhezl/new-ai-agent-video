package com.tonyhe.fusion.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TokenAuthenticationFilterTests {

    private final TokenService tokenService = mock(TokenService.class);
    private final SecurityUserDetailsService userDetailsService = mock(SecurityUserDetailsService.class);
    private final TokenAuthenticationFilter filter = new TokenAuthenticationFilter(tokenService, userDetailsService);

    @AfterEach
    void clearContext() {
        SecurityUtils.refreshCurrentTeamId(null);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesNativeMediaRequestFromAuthCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/media/images/a.png");
        request.setParameter("access_token", "query-token-must-not-be-used-for-media");
        request.setCookies(new Cookie("auth-token", "cookie-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        TokenService.TokenSession session = new TokenService.TokenSession(7L, "user", 20L);
        UserDetails details = new SecurityUserDetails(7L, "user", "", 1, 20L, List.of());
        when(tokenService.getAccessTokenSession("cookie-token")).thenReturn(session);
        when(userDetailsService.loadUserByUsername("user", 20L)).thenReturn(details);

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(SecurityUtils.getCurrentUserId()).isEqualTo(7L);
        verify(tokenService).getAccessTokenSession("cookie-token");
        verify(tokenService, never()).getAccessTokenSession("query-token-must-not-be-used-for-media");
    }

    @Test
    void authorizationHeaderTakesPrecedenceOverCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/media/images/a.png");
        request.addHeader("Authorization", "Bearer header-token");
        request.setCookies(new Cookie("auth-token", "cookie-token"));
        when(tokenService.getAccessTokenSession("header-token")).thenReturn(null);

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(tokenService).getAccessTokenSession("header-token");
        verify(tokenService, never()).getAccessTokenSession("cookie-token");
    }
}
