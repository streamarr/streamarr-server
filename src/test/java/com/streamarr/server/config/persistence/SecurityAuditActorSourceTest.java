package com.streamarr.server.config.persistence;

import static com.streamarr.server.support.security.SignedInAccount.callSignedInAs;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.audit.AuditActor;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.support.security.AnonymousRequestAuthentication;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("UnitTest")
@DisplayName("Security Audit Actor Source Tests")
class SecurityAuditActorSourceTest {

  private final SecurityAuditActorSource actorSource = new SecurityAuditActorSource();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should resolve the account when a signed-in account makes the request")
  void shouldResolveAccountWhenSignedInAccountMakesTheRequest() {
    var accountId = UUID.randomUUID();

    var actor = callSignedInAs(accountId, actorSource::currentActor);

    assertThat(actor).contains(new AuditActor.Account(accountId));
  }

  @Test
  @DisplayName(
      "Should resolve a request without account when the request carries anonymous authentication")
  void shouldResolveRequestWithoutAccountWhenRequestCarriesAnonymousAuthentication() {
    signInAnonymously();

    assertThat(actorSource.currentActor()).contains(new AuditActor.RequestWithoutAccount());
  }

  @Test
  @DisplayName("Should resolve no actor when the thread has neither authentication nor system work")
  void shouldResolveNoActorWhenThreadHasNeitherAuthenticationNorSystemWork() {
    assertThat(actorSource.currentActor()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should resolve no actor when the authentication is neither an account nor anonymous")
  void shouldResolveNoActorWhenAuthenticationIsNeitherAnAccountNorAnonymous() {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("someone", "secret"));

    assertThat(actorSource.currentActor()).isEmpty();
  }

  @Test
  @DisplayName("Should resolve the System actor when work runs as the System actor")
  void shouldResolveSystemActorWhenWorkRunsAsSystemActor() {
    assertThat(actorDuringSystemWork()).contains(new AuditActor.System());
  }

  @Test
  @DisplayName(
      "Should resolve the System actor when system work runs on a thread with a signed-in account")
  void shouldResolveSystemActorWhenSystemWorkRunsOnAThreadWithASignedInAccount() {
    var actor = callSignedInAs(UUID.randomUUID(), this::actorDuringSystemWork);

    assertThat(actor).contains(new AuditActor.System());
  }

  @Test
  @DisplayName(
      "Should resolve the System actor when system work runs on a request without an account")
  void shouldResolveSystemActorWhenSystemWorkRunsOnARequestWithoutAnAccount() {
    signInAnonymously();

    assertThat(actorDuringSystemWork()).contains(new AuditActor.System());
  }

  @Test
  @DisplayName("Should resolve the signed-in account when system work has ended")
  void shouldResolveSignedInAccountWhenSystemWorkHasEnded() {
    var accountId = UUID.randomUUID();

    var actor =
        callSignedInAs(
            accountId,
            () -> {
              actorDuringSystemWork();
              return actorSource.currentActor();
            });

    assertThat(actor).contains(new AuditActor.Account(accountId));
  }

  private Optional<AuditActor> actorDuringSystemWork() {
    var actor = new AtomicReference<Optional<AuditActor>>();
    SystemWork.runAsSystemActor(() -> actor.set(actorSource.currentActor()));
    return actor.get();
  }

  private static void signInAnonymously() {
    SecurityContextHolder.getContext().setAuthentication(AnonymousRequestAuthentication.token());
  }
}
