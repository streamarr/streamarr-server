package com.streamarr.server.config.security;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.RequestAuthorizationStateResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Slf4j
@RequiredArgsConstructor
public class LiveIdentityAuthorizationFilter extends OncePerRequestFilter {

  private final RequestAuthorizationStateResolver stateResolver;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;

  /**
   * Validates live authorization state and adjusts profile-scoped authentication when no active profile exists.
   */
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
        downgradeToAccountScope(token);
      }
    } catch (AuthenticationRequiredException exception) {
      authenticationEntryPoint.commence(
          request,
          response,
          new InsufficientAuthenticationException(exception.getMessage(), exception));
      return;
    } catch (RuntimeException exception) {
      log.error("Live authorization state could not be resolved.", exception);
      authenticationEntryPoint.commence(
          request,
          response,
          new InsufficientAuthenticationException(
              "Live authorization could not be verified.", exception));
      return;
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Downgrades the current authentication from profile scope to account scope and updates the security context.
   *
   * @param token the profile-scoped authentication to replace
   */
  private void downgradeToAccountScope(StreamarrAuthenticationToken token) {
    var signedIdentity = token.getPrincipal();
    var accountIdentity =
        AuthenticatedIdentity.builder()
            .accountId(signedIdentity.accountId())
            .role(signedIdentity.role())
            .authSessionId(signedIdentity.authSessionId())
            .scope(TokenScope.ACCOUNT)
            .build();
    var accountToken =
        new StreamarrAuthenticationToken(
            accountIdentity,
            (Jwt) token.getCredentials(),
            List.of(new SimpleGrantedAuthority(TokenScope.ACCOUNT.authority())));
    accountToken.setDetails(token.getDetails());
    accountToken.setRequestAuthorizationState(token.getRequestAuthorizationState());
    SecurityContextHolder.getContext().setAuthentication(accountToken);
    log.info(
        "Downgraded auth session {} from profile {} to account scope because the profile is no"
            + " longer active in the account home.",
        signedIdentity.authSessionId(),
        signedIdentity.profileId());
  }
}
