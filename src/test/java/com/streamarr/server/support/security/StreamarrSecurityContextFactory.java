package com.streamarr.server.support.security;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

final class StreamarrSecurityContextFactory {

  private StreamarrSecurityContextFactory() {}

  /**
   * Creates a security context for the specified token scope and account role.
   *
   * @param scope the token scope used to configure the identity and authority
   * @param role  the account role assigned to the identity
   * @return the configured security context
   */
  static SecurityContext contextFor(TokenScope scope, AccountRole role) {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(TestIdentityConstants.ACCOUNT_ID)
            .role(role)
            .authSessionId(TestIdentityConstants.SESSION_ID)
            .scope(scope)
            .profileId(scope == TokenScope.PROFILE ? TestIdentityConstants.PROFILE_ID : null)
            .build();

    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new StreamarrAuthenticationToken(
            identity, null, List.of(new SimpleGrantedAuthority(scope.authority()))));
    return context;
  }
}
