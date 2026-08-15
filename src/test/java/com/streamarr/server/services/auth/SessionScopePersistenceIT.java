package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.support.AuthTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Session Scope Persistence Integration Tests")
class SessionScopePersistenceIT extends AbstractIntegrationTest {

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TokenRefreshService tokenRefreshService;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private SecurityAuditEventRepository auditEventRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void cleanUp() {
    dsl.execute("DROP TRIGGER IF EXISTS reject_revocation_write ON auth_session");
    dsl.execute("DROP FUNCTION IF EXISTS reject_auth_session_revocation_write()");
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should not write revocation fields when refresh clears invalid profile selection")
  void shouldNotWriteRevocationFieldsWhenRefreshClearsInvalidProfileSelection() {
    identity = authTestSupport.createIdentity();
    removeActiveShare();
    installRevocationWriteGuard();

    assertThatCode(() -> tokenRefreshService.refresh(identity.rawRefreshToken()))
        .doesNotThrowAnyException();
    assertThat(authSessionRepository.findById(identity.session().getId()).orElseThrow())
        .extracting(AuthSession::getActiveProfileId)
        .isNull();
  }

  @Test
  @DisplayName("Should audit profile selection downgrade during refresh")
  void shouldAuditProfileSelectionDowngradeDuringRefresh() {
    identity = authTestSupport.createIdentity();
    var auditCountBeforeRefresh = auditEventRepository.count();
    removeActiveShare();

    tokenRefreshService.refresh(identity.rawRefreshToken());

    assertThat(auditEventRepository.count()).isGreaterThan(auditCountBeforeRefresh);
    assertThat(auditEventRepository.findAll())
        .anyMatch(event -> identity.profile().getId().equals(event.getTargetProfileId()));
  }

  private void removeActiveShare() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              var share =
                  shareRepository
                      .findByProfileIdAndHouseholdId(
                          identity.profile().getId(), identity.household().getId())
                      .orElseThrow();
              shareRepository.delete(share);
            });
  }

  private void installRevocationWriteGuard() {
    dsl.execute(
        """
        CREATE FUNCTION reject_auth_session_revocation_write()
            RETURNS TRIGGER
            LANGUAGE plpgsql
        AS
        $$
        BEGIN
            RAISE EXCEPTION 'refresh selection cleanup wrote revocation fields';
        END;
        $$
        """);
    dsl.execute(
        """
        CREATE TRIGGER reject_revocation_write
            BEFORE UPDATE OF revoked_at, revoked_reason
            ON auth_session
            FOR EACH ROW
        EXECUTE FUNCTION reject_auth_session_revocation_write()
        """);
  }
}
