package com.streamarr.server.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.auth.TokenScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
    var accountId = signIn();

    assertThat(auditorAware.getCurrentAuditor()).contains(accountId);
  }

  @Test
  @DisplayName("Should return empty when unauthenticated")
  void shouldReturnEmptyWhenUnauthenticated() {
    // A request made without an account is still a person's request, never the System actor's.
    assertThat(auditorAware.getCurrentAuditor()).isEmpty();
  }

  @Test
  @DisplayName("Should return the System actor when work runs as the System actor")
  void shouldReturnSystemActorWhenWorkRunsAsSystemActor() {
    assertThat(auditorDuringSystemWork()).contains(SystemActor.ID);
  }

  @Test
  @DisplayName(
      "Should return the System actor when system work runs on a thread with a signed-in account")
  void shouldReturnSystemActorWhenSystemWorkRunsOnThreadWithSignedInAccount() {
    signIn();

    assertThat(auditorDuringSystemWork()).contains(SystemActor.ID);
  }

  @Test
  @DisplayName("Should return the signed-in account when system work has ended")
  void shouldReturnSignedInAccountWhenSystemWorkHasEnded() {
    var accountId = signIn();
    auditorDuringSystemWork();

    assertThat(auditorAware.getCurrentAuditor()).contains(accountId);
  }

  private Optional<UUID> auditorDuringSystemWork() {
    var auditor = new AtomicReference<Optional<UUID>>();
    SystemWork.runAsSystemActor(() -> auditor.set(auditorAware.getCurrentAuditor()));
    return auditor.get();
  }

  private static UUID signIn() {
    var accountId = UUID.randomUUID();
    var identity = AuthenticatedIdentityFixture.accountScopedBuilder().accountId(accountId).build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new StreamarrAuthenticationToken(
                identity,
                null,
                List.of(new SimpleGrantedAuthority(TokenScope.ACCOUNT.authority()))));
    return accountId;
  }
}
