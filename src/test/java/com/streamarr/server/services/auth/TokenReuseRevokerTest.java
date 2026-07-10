package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Token Reuse Revoker Tests")
class TokenReuseRevokerTest {

  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();

  private final TokenReuseRevoker revoker =
      new TokenReuseRevoker(
          new TokenReuseRevocationWriter(sessionRepository, new FakeRefreshTokenRepository()));

  @Test
  @DisplayName("Should revoke inline when scope synchronizes without a transaction")
  void shouldRevokeInlineWhenScopeSynchronizesWithoutATransaction() {
    var session = saveSession();
    var template = new TransactionTemplate(new NoOpTransactionManager());
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);

    template.executeWithoutResult(
        _ -> {
          revoker.revokeAfterCompletion(session.getId(), Instant.now());
          // Inline, not deferred: the revocation must be visible before the scope completes.
          assertThat(sessionRepository.findById(session.getId()).orElseThrow().getRevokedAt())
              .isNotNull();
        });
  }

  @Test
  @DisplayName("Should revoke inline when transaction lacks synchronization")
  void shouldRevokeInlineWhenTransactionLacksSynchronization() {
    var session = saveSession();

    // Spring's transaction managers set the actual-transaction and synchronization flags
    // together, so this state only arises from custom TransactionSynchronizationManager use.
    // Pinned because the fail-safe matters: revoke inline rather than let
    // registerSynchronization throw and lose the security response.
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

  private AuthSession saveSession() {
    return sessionRepository.save(
        AuthSession.builder().id(UUID.randomUUID()).accountId(UUID.randomUUID()).build());
  }

  /** Real Spring transaction lifecycle over no resources — no manual synchronization state. */
  private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      // No resource to begin — only the synchronization lifecycle matters here.
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      // No resource to commit.
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      // No resource to roll back.
    }
  }
}
