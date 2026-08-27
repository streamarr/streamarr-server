package com.streamarr.server.services.identity;

import static com.streamarr.server.fixtures.AccountInvitationFixture.pendingInvitationBuilder;
import static com.streamarr.server.fixtures.PasswordResetCodeFixture.pendingResetCodeBuilder;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.support.PostgresLockTestSupport.activeQuery;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.lockAccountRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.exceptions.ResourceBusyException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.support.AuthTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Credential Issuance Lock Timeout Integration Tests")
@SpringBootTest(properties = "auth.credential-codes.replacement-lock-timeout=2s")
class CredentialIssuanceLockTimeoutIT extends AbstractIntegrationTest {

  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  private AuthTestSupport.TestIdentity issuer;
  private AuthTestSupport.TestIdentity resetTarget;

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    invitationRepository.deleteAll();
    resetCodeRepository.deleteAll();
    if (issuer != null) {
      authTestSupport.deleteIdentity(issuer);
    }

    if (resetTarget != null) {
      authTestSupport.deleteIdentity(resetTarget);
    }
  }

  @Test
  @DisplayName("Should preserve existing invitation when replacement exceeds lock timeout")
  void shouldPreserveExistingInvitationWhenReplacementExceedsLockTimeout() throws Exception {
    issuer = authTestSupport.createAdminIdentity();
    var recipientEmail = "replacement-timeout@example.com";
    var existing = savePendingInvitation(recipientEmail);

    assertInvitationReplacementTimesOut(
        "  Replacement-Timeout@Example.COM ",
        () ->
            credentialIssuanceService.issueAccountInvitation(
                authTestSupport.identityOf(issuer), invitationCommand(recipientEmail)));

    assertOnlyPendingInvitation(existing);
  }

  @Test
  @DisplayName(
      "Should contend for the recipient lock when spellings agree only under PostgreSQL lower")
  void shouldContendForRecipientLockWhenSpellingsAgreeOnlyUnderPostgresLower() throws Exception {
    issuer = authTestSupport.createAdminIdentity();
    var existing = savePendingInvitation("invitee@example.com");

    // U+0130 folds to a plain "i" under PostgreSQL lower(), the view of the address taken by the
    // pending-email unique index and every query, but to "i" plus U+0307 under Java toLowerCase.
    assertInvitationReplacementTimesOut(
        "invitee@example.com",
        () ->
            credentialIssuanceService.issueAccountInvitation(
                authTestSupport.identityOf(issuer), invitationCommand("\u0130nvitee@Example.COM")));

    assertOnlyPendingInvitation(existing);
  }

  @Test
  @DisplayName("Should reject an invitation issuance lock outside a transaction")
  void shouldRejectInvitationIssuanceLockOutsideTransaction() {
    assertThatThrownBy(
            () ->
                invitationRepository.lockInvitationIssuanceForRecipientEmail(
                    "outside-transaction@example.com"))
        .isInstanceOf(InvalidDataAccessApiUsageException.class)
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("Invitation issuance lock requires an active transaction.");
  }

  @Test
  @DisplayName("Should preserve existing reset code when replacement exceeds lock timeout")
  void shouldPreserveExistingResetCodeWhenReplacementExceedsLockTimeout() throws Exception {
    issuer = authTestSupport.createAdminIdentity();
    resetTarget = authTestSupport.createIdentity();
    var accountId = resetTarget.account().getId();
    var existing = savePendingResetCode();

    assertReplacementTimesOut(
        connection -> lockAccountRow(connection, accountId),
        "\"user_account\"",
        () ->
            credentialIssuanceService.issuePasswordReset(
                authTestSupport.freshIdentityOf(issuer), accountId, "recover access"));

    assertThat(resetCodeRepository.findAll())
        .singleElement()
        .satisfies(
            resetCode -> {
              assertThat(resetCode.getId()).isEqualTo(existing.getId());
              assertThat(resetCode.getStatus()).isEqualTo(PasswordResetCodeStatus.PENDING);
            });
  }

  private void assertOnlyPendingInvitation(AccountInvitation existing) {
    assertThat(invitationRepository.findAll())
        .singleElement()
        .satisfies(
            invitation -> {
              assertThat(invitation.getId()).isEqualTo(existing.getId());
              assertThat(invitation.getStatus()).isEqualTo(AccountInvitationStatus.PENDING);
            });
  }

  private AccountInvitation savePendingInvitation(String recipientEmail) {
    return invitationRepository.saveAndFlush(
        pendingInvitationBuilder()
            .recipientEmail(recipientEmail)
            .householdId(issuer.household().getId())
            .householdName(issuer.household().getName())
            .issuerAccountId(issuer.account().getId())
            .build());
  }

  private PasswordResetCode savePendingResetCode() {
    return resetCodeRepository.saveAndFlush(
        pendingResetCodeBuilder()
            .accountId(resetTarget.account().getId())
            .issuerAccountId(issuer.account().getId())
            .build());
  }

  private IssueInvitationCommand invitationCommand(String recipientEmail) {
    return IssueInvitationCommand.builder()
        .recipientEmail(recipientEmail)
        .householdId(issuer.household().getId())
        .householdRole(HouseholdRole.MEMBER)
        .profileName("Replacement")
        .profileKind(ProfileKind.ADULT)
        .build();
  }

  private void assertInvitationReplacementTimesOut(String recipientEmail, Supplier<?> replacement)
      throws Exception {
    var lockAcquired = new CountDownLatch(1);
    var releaseLock = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker =
          executor.submit(
              () -> {
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        _ -> {
                          invitationRepository.lockInvitationIssuanceForRecipientEmail(
                              recipientEmail);
                          lockAcquired.countDown();
                          awaitLatch(releaseLock);
                        });
                return null;
              });
      try {
        assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue();
        var contender = executor.submit(replacement::get);
        assertThatThrownBy(() -> contender.get(3, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(ResourceBusyException.class)
            .hasStackTraceContaining("canceling statement due to lock timeout");
      } finally {
        releaseLock.countDown();
      }

      blocker.get(10, TimeUnit.SECONDS);
    }
  }

  private void assertReplacementTimesOut(
      LockOperation lockOperation, String expectedBlockedQuery, Supplier<?> replacement)
      throws Exception {
    try (var lockConnection = dataSource.getConnection();
        var observer = dataSource.getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      lockConnection.setAutoCommit(false);
      lockOperation.lock(lockConnection);

      var contender = executor.submit(replacement::get);
      assertBlockedQuery(observer, backendPid(lockConnection), expectedBlockedQuery);
      try {
        assertThatThrownBy(() -> contender.get(3, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(ResourceBusyException.class)
            .hasStackTraceContaining("canceling statement due to lock timeout");
      } finally {
        lockConnection.rollback();
      }
    }
  }

  private static void assertBlockedQuery(
      Connection observer, int blockerPid, String expectedBlockedQuery) throws SQLException {
    var blockedPid = awaitBlockedBackendPid(observer, blockerPid, null);
    assertThat(activeQuery(observer, blockedPid)).contains(expectedBlockedQuery, "for update");
  }

  @FunctionalInterface
  private interface LockOperation {

    void lock(Connection connection) throws SQLException;
  }
}
