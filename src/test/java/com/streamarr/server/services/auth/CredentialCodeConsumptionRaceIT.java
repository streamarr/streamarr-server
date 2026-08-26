package com.streamarr.server.services.auth;

import static com.streamarr.server.fixtures.AccountInvitationFixture.pendingInvitationBuilder;
import static com.streamarr.server.fixtures.PasswordResetCodeFixture.pendingResetCodeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.rejectionOf;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.lockAccountRow;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccountInvitationService.AcceptInvitationCommand;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import com.streamarr.server.services.identity.CredentialRejections;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("IntegrationTest")
@DisplayName("Credential Code Consumption Race Integration Tests")
class CredentialCodeConsumptionRaceIT extends AbstractIntegrationTest {

  @Autowired private AccountInvitationService invitationService;
  @Autowired private PasswordResetService passwordResetService;
  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private OpaqueOneTimeCodes opaqueCodes;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    resetCodeRepository.deleteAll();
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should permit one decision when acceptance and decline race on one invitation")
  void shouldPermitOneDecisionWhenAcceptanceAndDeclineRaceOnOneInvitation() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var issued = opaqueCodes.issue();
    var invitation = saveInvitation(issued);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var lock = lockRow(rowLock("account_invitation", invitation.getId()))) {
      var acceptance =
          executor.submit(
              () -> attempt(() -> invitationService.accept(acceptCommand(issued.code()))));
      var decline = executor.submit(() -> attempt(() -> invitationService.decline(issued.code())));

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(waitingBehind(lock.backendPid(), "account_invitation")).isEqualTo(2));
      lock.release();

      assertOneWinner(acceptance.get(10, TimeUnit.SECONDS), decline.get(10, TimeUnit.SECONDS));
    }

    var status = invitationRepository.findById(invitation.getId()).orElseThrow().getStatus();
    assertThat(status).isIn(AccountInvitationStatus.ACCEPTED, AccountInvitationStatus.DECLINED);
    assertThat(userAccountRepository.findByEmailIgnoreCase("race-invitee@example.com").isPresent())
        .isEqualTo(status == AccountInvitationStatus.ACCEPTED);
  }

  @Test
  @DisplayName("Should permit one decision when acceptance and cancellation race")
  void shouldPermitOneDecisionWhenAcceptanceAndCancellationRace() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var issued = opaqueCodes.issue();
    var invitation = saveInvitation(issued);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var lock = lockRow(rowLock("account_invitation", invitation.getId()))) {
      var acceptance =
          executor.submit(
              () -> attempt(() -> invitationService.accept(acceptCommand(issued.code()))));
      var cancellation =
          executor.submit(
              () ->
                  credentialIssuanceService.cancelAccountInvitation(
                      authTestSupport.identityOf(identity), invitation.getId()));

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(waitingBehind(lock.backendPid(), "account_invitation")).isEqualTo(2));
      lock.release();

      var acceptanceAttempt = acceptance.get(10, TimeUnit.SECONDS);
      var cancellationOutcome = cancellation.get(10, TimeUnit.SECONDS);
      var cancellationSucceeded = cancellationOutcome instanceof Outcome.Accepted<?, ?>;
      assertThat(acceptanceAttempt.successful()).isNotEqualTo(cancellationSucceeded);
      if (acceptanceAttempt.successful()) {
        assertThat(rejectionOf(cancellationOutcome))
            .isInstanceOf(CredentialRejections.InvitationNotPending.class);
      } else {
        assertThat(acceptanceAttempt.failure()).isInstanceOf(InvalidOneTimeCodeException.class);
      }
    }

    var status = invitationRepository.findById(invitation.getId()).orElseThrow().getStatus();
    assertThat(status).isIn(AccountInvitationStatus.ACCEPTED, AccountInvitationStatus.CANCELED);
    assertThat(userAccountRepository.findByEmailIgnoreCase("race-invitee@example.com").isPresent())
        .isEqualTo(status == AccountInvitationStatus.ACCEPTED);
  }

  @Test
  @DisplayName("Should permit one redemption when the same password-reset code races")
  void shouldPermitOneRedemptionWhenSamePasswordResetCodeRaces() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var issued = opaqueCodes.issue();
    var resetCode = saveResetCode(issued);
    var sessionIdsBefore =
        authSessionRepository.findByAccountId(identity.account().getId()).stream()
            .map(session -> session.getId())
            .toList();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var lock = lockRow(rowLock("password_reset_code", resetCode.getId()))) {
      var first =
          executor.submit(
              () ->
                  attempt(
                      () ->
                          passwordResetService.redeem(
                              issued.code(), "the replacement passphrase")));
      var second =
          executor.submit(
              () ->
                  attempt(
                      () ->
                          passwordResetService.redeem(
                              issued.code(), "the replacement passphrase")));

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                assertThat(waitingBehind(lock.backendPid(), "password_reset_code")).isOne();
                assertThat(waitingBehind(lock.backendPid(), "user_account")).isOne();
              });
      lock.release();

      assertOneWinner(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    assertThat(resetCodeRepository.findById(resetCode.getId()).orElseThrow().getStatus())
        .isEqualTo(PasswordResetCodeStatus.REDEEMED);
    assertThat(
            passwordEncoder.matches(
                "the replacement passphrase",
                userAccountRepository
                    .findById(identity.account().getId())
                    .orElseThrow()
                    .getPasswordHash()))
        .isTrue();
    assertThat(authSessionRepository.findByAccountId(identity.account().getId()))
        .hasSameSizeAs(sessionIdsBefore)
        .allSatisfy(
            session -> {
              assertThat(sessionIdsBefore).contains(session.getId());
              assertThat(session.getRevokedAt()).isNotNull();
            });
  }

  @Test
  @DisplayName("Should preserve a concurrent Account update when a reset changes its password")
  void shouldPreserveConcurrentAccountUpdateWhenResetChangesItsPassword() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var issued = opaqueCodes.issue();
    saveResetCode(issued);
    var rowLocked = new CompletableFuture<Integer>();
    var releaseRow = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var accountUpdate =
          executor.submit(
              () ->
                  holdAccountRowAndChangeDisplayName(
                      identity.account().getId(), rowLocked, releaseRow));
      var holderPid = rowLocked.get(10, TimeUnit.SECONDS);

      var redemption =
          executor.submit(
              () ->
                  attempt(
                      () ->
                          passwordResetService.redeem(
                              issued.code(), "the concurrent replacement passphrase")));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(waitingBehind(holderPid, "user_account")).isOne());
      releaseRow.countDown();

      assertThat(redemption.get(10, TimeUnit.SECONDS).successful()).isTrue();
      accountUpdate.get(10, TimeUnit.SECONDS);
    } finally {
      releaseRow.countDown();
    }

    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    assertThat(account.getDisplayName()).isEqualTo("Concurrent Display Name");
    assertThat(
            passwordEncoder.matches(
                "the concurrent replacement passphrase", account.getPasswordHash()))
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should complete both issuer disablement and acceptance when the manager is the issuer")
  void shouldCompleteBothIssuerDisablementAndAcceptanceWhenManagerIsIssuer() throws Exception {
    // Disablement locks the issuer's Account row and then invalidates the invitations they issued;
    // acceptance must take the manager's Account row before the invitation row, or the two
    // transactions deadlock when the manager is that issuer.
    identity = authTestSupport.createAdminIdentity();
    var issuerId = identity.account().getId();
    var issued = opaqueCodes.issue();
    var invitation =
        invitationRepository.saveAndFlush(
            pendingInvitationBuilder()
                .recipientEmail("race-invitee@example.com")
                .householdId(identity.household().getId())
                .householdName(identity.household().getName())
                .issuerAccountId(issuerId)
                .localManagerAccountId(issuerId)
                .publicId(issued.publicId())
                .secretDigest(issued.digest())
                .build());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var disablement = dataSource.getConnection()) {
      disablement.setAutoCommit(false);
      lockAccountRow(disablement, issuerId);
      var acceptance =
          executor.submit(
              () -> attempt(() -> invitationService.accept(acceptCommand(issued.code()))));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(waitersBehind(jdbcTemplate, backendPid(disablement), "%")).isOne());

      try (var invalidate =
          disablement.prepareStatement(
              """
              UPDATE account_invitation
              SET status = 'INVALIDATED', invalidation_reason = 'issuer disabled',
                  decided_at = NOW(), last_modified_on = NOW()
              WHERE issuer_account_id = ? AND status = 'PENDING' AND expires_at > NOW()
              """)) {
        invalidate.setObject(1, issuerId);
        assertThat(invalidate.executeUpdate()).isOne();
      }

      disablement.commit();
      var acceptanceAttempt = acceptance.get(10, TimeUnit.SECONDS);
      assertThat(acceptanceAttempt.successful()).isFalse();
      assertThat(acceptanceAttempt.failure()).isInstanceOf(InvalidOneTimeCodeException.class);
    }

    assertThat(invitationRepository.findById(invitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(userAccountRepository.findByEmailIgnoreCase("race-invitee@example.com")).isEmpty();
  }

  private AccountInvitation saveInvitation(OpaqueOneTimeCodes.IssuedCode issued) {
    return invitationRepository.saveAndFlush(
        pendingInvitationBuilder()
            .recipientEmail("race-invitee@example.com")
            .householdId(identity.household().getId())
            .householdName(identity.household().getName())
            .issuerAccountId(identity.account().getId())
            .publicId(issued.publicId())
            .secretDigest(issued.digest())
            .build());
  }

  private PasswordResetCode saveResetCode(OpaqueOneTimeCodes.IssuedCode issued) {
    return resetCodeRepository.saveAndFlush(
        pendingResetCodeBuilder()
            .accountId(identity.account().getId())
            .issuerAccountId(identity.account().getId())
            .publicId(issued.publicId())
            .secretDigest(issued.digest())
            .build());
  }

  private static AcceptInvitationCommand acceptCommand(String code) {
    return AcceptInvitationCommand.builder()
        .code(code)
        .displayName("Invitee")
        .password("a strong passphrase")
        .deviceName("test")
        .build();
  }

  private RowLockTarget rowLock(String table, UUID rowId) {
    return RowLockTarget.builder().dataSource(dataSource).table(table).rowId(rowId).build();
  }

  private void holdAccountRowAndChangeDisplayName(
      UUID accountId, CompletableFuture<Integer> rowLocked, CountDownLatch releaseRow) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement("UPDATE user_account SET display_name = ? WHERE id = ?")) {
      connection.setAutoCommit(false);
      statement.setString(1, "Concurrent Display Name");
      statement.setObject(2, accountId);
      statement.executeUpdate();
      rowLocked.complete(backendPid(connection));
      if (!releaseRow.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("test did not release the Account row lock");
      }

      connection.commit();
    } catch (Exception exception) {
      rowLocked.completeExceptionally(exception);
      throw new AssertionError("could not coordinate the Account row lock", exception);
    }
  }

  private int waitingBehind(int blockerPid, String tableName) {
    return waitersBehind(jdbcTemplate, blockerPid, "%" + tableName + "%");
  }

  private static Attempt attempt(ThrowingRunnable action) {
    try {
      action.run();
      return new Attempt(true, null);
    } catch (Throwable failure) {
      return new Attempt(false, failure);
    }
  }

  private static void assertOneWinner(Attempt first, Attempt second) {
    assertThat(first.successful() || second.successful()).isTrue();
    assertThat(first.successful() && second.successful()).isFalse();
    assertThat(first.successful() ? second.failure() : first.failure())
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private record Attempt(boolean successful, Throwable failure) {}
}
