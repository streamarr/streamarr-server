package com.streamarr.server.config.security;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.RequestAuthorizationStateResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class LiveIdentityAuthorizationFilter extends OncePerRequestFilter {

  private final RequestAuthorizationStateResolver stateResolver;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final RestAccessDeniedHandler accessDeniedHandler;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof StreamarrAuthenticationToken token)) {
      filterChain.doFilter(request, response);
      return;
    }

    var identity = token.getPrincipal();
    if (identity.scope() == TokenScope.PLAYBACK) {
      filterChain.doFilter(request, response);
      return;
    }

    try {
      var state = stateResolver.resolve(token);
      if (identity.scope() == TokenScope.PROFILE && state.activeProfileId() == null) {
        accessDeniedHandler.handle(
            request,
            response,
            new AccessDeniedException("Active profile access is no longer live."));
        return;
      }
    } catch (AuthenticationRequiredException exception) {
      authenticationEntryPoint.commence(
          request,
          response,
          new InsufficientAuthenticationException(exception.getMessage(), exception));
      return;
    }

    filterChain.doFilter(request, response);
  }
}
