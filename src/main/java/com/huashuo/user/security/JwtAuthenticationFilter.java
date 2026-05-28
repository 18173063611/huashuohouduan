package com.huashuo.user.security;

import com.huashuo.user.config.LoginAuthInterceptor;
import com.huashuo.user.util.AuthHeaderParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String CURRENT_SESSION_ID_ATTRIBUTE = "currentSessionId";
    public static final String CURRENT_CLIENT_TYPE_ATTRIBUTE = "currentClientType";

    private final AuthSessionService authSessionService;
    private final CustomUserDetailsService userDetailsService;
    private final AuthErrorResponseWriter errorResponseWriter;

    public JwtAuthenticationFilter(AuthSessionService authSessionService,
                                   CustomUserDetailsService userDetailsService,
                                   AuthErrorResponseWriter errorResponseWriter) {
        this.authSessionService = authSessionService;
        this.userDetailsService = userDetailsService;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = AuthHeaderParser.resolveBearer(
                request.getHeader("Authorization"),
                request.getHeader("X-Auth-Token")
        );
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthSessionService.ValidatedSession session = authSessionService.validateAccessToken(token);
            enforceClientType(request, session.clientType());
            CustomUserDetails user = userDetailsService.loadById(session.userId());
            if (!user.isEnabled() || !user.isAccountNonLocked()) {
                throw JwtAuthException.disabled();
            }
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            authentication.setDetails(session);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(LoginAuthInterceptor.CURRENT_USER_ID_ATTRIBUTE, session.userId());
            request.setAttribute(CURRENT_SESSION_ID_ATTRIBUTE, session.sessionId());
            request.setAttribute(CURRENT_CLIENT_TYPE_ATTRIBUTE, session.clientType().name());
            filterChain.doFilter(request, response);
        } catch (JwtAuthException exception) {
            SecurityContextHolder.clearContext();
            errorResponseWriter.write(response, exception);
        } catch (UsernameNotFoundException exception) {
            SecurityContextHolder.clearContext();
            errorResponseWriter.write(response, JwtAuthException.invalid());
        }
    }

    private void enforceClientType(HttpServletRequest request, AuthClientType clientType) {
        String uri = request.getRequestURI();
        if (uri.equals("/api/v1/admin") || uri.startsWith("/api/v1/admin/")) {
            if (clientType != AuthClientType.ADMIN_WEB) {
                throw JwtAuthException.denied();
            }
            return;
        }
        if (uri.startsWith("/api/v1/") && !uri.startsWith("/api/v1/auth/")
                && clientType == AuthClientType.ADMIN_WEB) {
            throw JwtAuthException.denied();
        }
    }
}
