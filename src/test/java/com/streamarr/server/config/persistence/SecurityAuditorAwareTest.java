package com.streamarr.server.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("UnitTest")
@DisplayName("Security Auditor Aware Tests")
class SecurityAuditorAwareTest {

  private final SecurityAuditorAware auditorAware = new SecurityAuditorAware();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should return account id when authenticated")
  void shouldReturnAccountIdWhenAuthenticated() {
    var accountId = UUID.randomUUID();
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(accountId)
            .role(AccountRole.USER)
            .sessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new StreamarrAuthenticationToken(
                identity,
                null,
                List.of(new SimpleGrantedAuthority(TokenScope.ACCOUNT.authority()))));

    assertThat(auditorAware.getCurrentAuditor()).contains(accountId);
  }

  @Test
  @DisplayName("Should return empty when unauthenticated")
  void shouldReturnEmptyWhenUnauthenticated() {
    // Background jobs have no request identity; audit columns stay null rather than lying.
    assertThat(auditorAware.getCurrentAuditor()).isEmpty();
  }
}
