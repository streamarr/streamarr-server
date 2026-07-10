package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("UnitTest")
@DisplayName("Token Reuse Revoker Tests")
class TokenReuseRevokerTest {

  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();

  private final TokenReuseRevoker revoker =
      new TokenReuseRevoker(
          new TokenReuseRevocationWriter(
              sessionRepository, new FakeRefreshTokenRepository(), new CapturingEventPublisher()));

  @Test
  @DisplayName("Should revoke immediately when transaction lacks synchronization")
  void shouldRevokeImmediatelyWhenTransactionLacksSynchronization() {
    var session =
        sessionRepository.save(
            AuthSession.builder().id(UUID.randomUUID()).accountId(UUID.randomUUID()).build());

    // registerSynchronization would throw in this state — the revoker must run inline instead.
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      revoker.revokeAfterCompletion(session.getId(), Instant.now());
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    var revoked = sessionRepository.findById(session.getId()).orElseThrow();
    assertThat(revoked.getRevokedAt()).isNotNull();
    assertThat(revoked.getRevokedReason()).isEqualTo(SessionRevocationReason.TOKEN_REUSE);
  }
}
