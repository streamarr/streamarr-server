package com.streamarr.server.services.identity;

import static com.streamarr.server.fixtures.AccountInvitationFixture.pendingInvitationBuilder;
import static com.streamarr.server.fixtures.PasswordResetCodeFixture.pendingResetCodeBuilder;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.lockAccountRow;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccountInvitationService;
import com.streamarr.server.services.auth.PasswordResetService;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Credential Issuance Revocation Race Integration Tests")
class CredentialIssuanceRevocationRaceIT extends AbstractIntegrationTest {

  private static final String INVITATION_UPDATE = "%update%account_invitation%recipient_email%";
  private static final String RESET_CODE_UPDATE = "%update%password_reset_code%account_id%";
  private static final String ACCOUNT_UPDATE = "%update%user_account%";
  private static final String ACCOUNT_ROW = "%user_account%";
  private static final String RECIPIENT_LOCK = "%pg_advisory_xact_lock%";

  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountAdministrationService accountAdministrationService;
  @Autowired private AccountInvitationService invitationService;
  @Autowired private PasswordResetService passwordResetService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private PasswordEncoder passwordEncoder;

  private AuthTestSupport.TestIdentity issuer;
  private AuthTestSupport.TestIdentity revoker;

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    invitationRepository.deleteAll();
    resetCodeRepository.deleteAll();
    if (issuer != null) {
      authTestSupport.deleteIdentity(issuer);
    }

    if (revoker != null) {
      authTestSupport.deleteIdentity(revoker);
    }
  }

  @Test
  @DisplayName("Should leave no usable reset code when its issuer loses ServerAdmin authority")
  void shouldLeaveNoUsableResetCodeWhenIssuerLosesServerAdminAuthority() throws Exception {
    issuer = authTestSupport.createAdminIdentity();
    revoker = authTestSupport.createAdminIdentity();
    var blocker = saveBlockingResetCode();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var lock = lockRow(rowLock("password_reset_code", blocker.getId()))) {
      var issuance =
          executor.submit(
              () ->
                  credentialIssuanceService.issuePasswordReset(
                      authTestSupport.freshIdentityOf(issuer),
                      issuer.account().getId(),
                      "recover access"));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(waitingBehind(lock.backendPid(), RESET_CODE_UPDATE)).isOne());

      var revocation =
          executor.submit(
              () ->
                  accountAdministrationService.revokeServerAdmin(
                      authTestSupport.freshIdentityOf(revoker),
                      issuer.account().getId(),
                      "authority no longer required"));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(
                          revocation.isDone()
                              || waitingBehind(lock.backendPid(), ACCOUNT_UPDATE) == 1)
                      .isTrue());

      lock.release();
      var issued = accepted(issuance.get(10, TimeUnit.SECONDS));
      assertThat(revocation.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);

      assertThat(
              userAccountRepository
                  .findById(issuer.account().getId())
                  .orElseThrow()
                  .isServerAdmin())
          .isFalse();
      assertThat(resetCodeRepository.findById(issued.resetCode().getId()).orElseThrow().getStatus())
          .isEqualTo(PasswordResetCodeStatus.INVALIDATED);
      var code = issued.code();
      assertThatThrownBy(() -> passwordResetService.redeem(code, "a replacement passphrase"))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    }
  }

  @Test
  @DisplayName("Should leave no usable invitation when its issuer is disabled during issuance")
  void shouldLeaveNoUsableInvitationWhenIssuerIsDisabledDuringIssuance() throws Exception {
    issuer = authTestSupport.createAdminIdentity();
    revoker = authTestSupport.createAdminIdentity();
    var recipientEmail = "issuance-race@example.com";
    var blocker = saveBlockingInvitation(recipientEmail);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var lock = lockRow(rowLock("account_invitation", blocker.getId()))) {
      var issuance =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitation(
                      authTestSupport.identityOf(issuer), invitationCommand(recipientEmail)));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(waitingBehind(lock.backendPid(), INVITATION_UPDATE)).isOne());

      var revocationStarted = new CountDownLatch(1);
      var disable =
          executor.submit(
              () -> {
                revocationStarted.countDown();
                return accountAdministrationService.disableAccount(
                    authTestSupport.identityOf(revoker), issuer.account().getId());
              });
      assertThat(revocationStarted.await(10, TimeUnit.SECONDS)).isTrue();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(
                          disable.isDone() || waitingBehind(lock.backendPid(), ACCOUNT_UPDATE) == 1)
                      .isTrue());

      lock.release();
      var issued = accepted(issuance.get(10, TimeUnit.SECONDS));
      assertThat(disable.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);

      assertThat(userAccountRepository.findById(issuer.account().getId()).orElseThrow().isEnabled())
          .isFalse();
      assertThat(
              invitationRepository.findById(issued.invitation().getId()).orElseThrow().getStatus())
          .isEqualTo(AccountInvitationStatus.INVALIDATED);
      var code = issued.code();
      assertThatThrownBy(() -> invitationService.lookup(code))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    }
  }

  @Test
  @DisplayName("Should let issuer disable complete while invitation waits for its recipient lock")
  void shouldLetIssuerDisableCompleteWhileInvitationWaitsForRecipientLock() throws Exception {
    issuer = authTestSupport.createAdminIdentity();
    revoker = authTestSupport.createAdminIdentity();
    var recipientEmail = "issuance-lock-order@example.com";

    var lockAcquired = new CompletableFuture<Integer>();
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
                          lockAcquired.complete(
                              jdbcTemplate.queryForObject(
                                  "SELECT pg_backend_pid()", Integer.class));
                          awaitLatch(releaseLock);
                        });
                return null;
              });
      try {
        var blockerPid = lockAcquired.get(10, TimeUnit.SECONDS);
        var issuance =
            executor.submit(
                () ->
                    credentialIssuanceService.issueAccountInvitation(
                        authTestSupport.identityOf(issuer), invitationCommand(recipientEmail)));
        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(waitingBehind(blockerPid, RECIPIENT_LOCK)).isOne());

        var disable =
            executor.submit(
                () ->
                    accountAdministrationService.disableAccount(
                        authTestSupport.identityOf(revoker), issuer.account().getId()));

        assertThat(disable.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
        assertThat(issuance.isDone()).isFalse();
        releaseLock.countDown();
        assertThatThrownBy(() -> issuance.get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(AccessDeniedException.class);
      } finally {
        releaseLock.countDown();
      }

      blocker.get(10, TimeUnit.SECONDS);
    }

    assertThat(invitationRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should avoid deadlock when a self-issued reset races with Account disable")
  void shouldAvoidDeadlockWhenSelfIssuedResetRacesWithAccountDisable() throws Exception {
    issuer = authTestSupport.createAdminIdentity();
    revoker = authTestSupport.createAdminIdentity();
    var issued =
        accepted(
            credentialIssuanceService.issuePasswordReset(
                authTestSupport.freshIdentityOf(issuer),
                issuer.account().getId(),
                "recover access"));

    try (var lockConnection = dataSource.getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      lockConnection.setAutoCommit(false);
      lockAccountRow(lockConnection, issuer.account().getId());
      var holderPid = backendPid(lockConnection);

      var disable =
          executor.submit(
              () ->
                  accountAdministrationService.disableAccount(
                      authTestSupport.identityOf(revoker), issuer.account().getId()));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(waitingBehind(holderPid, ACCOUNT_ROW)).isOne());

      var redemption =
          executor.submit(
              () -> {
                try {
                  passwordResetService.redeem(issued.code(), "a replacement passphrase");
                  return null;
                } catch (InvalidOneTimeCodeException exception) {
                  return exception;
                }
              });
      try {
        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(waitingBehind(holderPid, ACCOUNT_ROW)).isEqualTo(2));
      } finally {
        lockConnection.rollback();
      }

      assertThat(disable.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      var redeemed = redemption.get(10, TimeUnit.SECONDS) == null;
      assertRedemptionOutcome(issued.resetCode().getId(), redeemed);
    }
  }

  /**
   * Whichever transaction won the Account row, the reset either fully happened (password changed,
   * refresh sessions revoked, code REDEEMED) or fully did not (code INVALIDATED by the disable,
   * password untouched) — never both, never neither.
   */
  private void assertRedemptionOutcome(UUID resetCodeId, boolean redeemed) {
    var account = userAccountRepository.findById(issuer.account().getId()).orElseThrow();
    var expectedStatus = PasswordResetCodeStatus.INVALIDATED;
    if (redeemed) {
      expectedStatus = PasswordResetCodeStatus.REDEEMED;
    }

    assertThat(account.isEnabled()).isFalse();
    assertThat(resetCodeRepository.findById(resetCodeId).orElseThrow().getStatus())
        .isEqualTo(expectedStatus);
    assertThat(passwordEncoder.matches("a replacement passphrase", account.getPasswordHash()))
        .isEqualTo(redeemed);
    assertThat(authSessionRepository.findByAccountId(issuer.account().getId()))
        .isNotEmpty()
        .allSatisfy(session -> assertThat(session.getRevokedAt()).isNotNull());
  }

  private AccountInvitation saveBlockingInvitation(String recipientEmail) {
    return invitationRepository.saveAndFlush(
        pendingInvitationBuilder()
            .recipientEmail(recipientEmail)
            .householdId(issuer.household().getId())
            .householdName(issuer.household().getName())
            .issuerAccountId(revoker.account().getId())
            .build());
  }

  private PasswordResetCode saveBlockingResetCode() {
    return resetCodeRepository.saveAndFlush(
        pendingResetCodeBuilder()
            .accountId(issuer.account().getId())
            .issuerAccountId(revoker.account().getId())
            .build());
  }

  private IssueInvitationCommand invitationCommand(String recipientEmail) {
    return IssueInvitationCommand.builder()
        .recipientEmail(recipientEmail)
        .householdId(issuer.household().getId())
        .householdRole(HouseholdRole.MEMBER)
        .profileName("Racing Invitee")
        .profileKind(ProfileKind.ADULT)
        .build();
  }

  private RowLockTarget rowLock(String table, UUID rowId) {
    return RowLockTarget.builder().dataSource(dataSource).table(table).rowId(rowId).build();
  }

  private int waitingBehind(int blockerPid, String queryPattern) {
    return waitersBehind(jdbcTemplate, blockerPid, queryPattern);
  }
}
