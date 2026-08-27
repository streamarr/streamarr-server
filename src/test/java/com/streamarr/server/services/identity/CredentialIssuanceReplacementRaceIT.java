package com.streamarr.server.services.identity;

import static com.streamarr.server.fixtures.AccountInvitationFixture.pendingInvitationBuilder;
import static com.streamarr.server.fixtures.PasswordResetCodeFixture.pendingResetCodeBuilder;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccountInvitationService;
import com.streamarr.server.services.auth.AccountInvitationService.AcceptInvitationCommand;
import com.streamarr.server.services.auth.OpaqueOneTimeCodes;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.HeldRowLock;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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

@Tag("IntegrationTest")
@DisplayName("Credential Issuance Replacement Race Integration Tests")
class CredentialIssuanceReplacementRaceIT extends AbstractIntegrationTest {

  private static final String RECIPIENT_UPDATE = "%update%account_invitation%recipient_email%";
  private static final String RECIPIENT_LOCK = "%pg_advisory_xact_lock%";
  private static final String RESET_CODE_UPDATE = "%update%password_reset_code%account_id%";
  private static final String ACCOUNT_LOCK = "%user_account%for update%";

  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountInvitationService accountInvitationService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private OpaqueOneTimeCodes opaqueCodes;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity firstIssuer;
  private AuthTestSupport.TestIdentity secondIssuer;
  private AuthTestSupport.TestIdentity resetTarget;

  @Test
  @DisplayName(
      "Should permit only acceptance or replacement when invitation acceptance races issuance")
  void shouldPermitOnlyAcceptanceOrReplacementWhenInvitationAcceptanceRacesIssuance()
      throws Exception {
    firstIssuer = authTestSupport.createAdminIdentity();
    var recipientEmail = "accept-replace-race@example.com";
    var issued = opaqueCodes.issue();
    invitationRepository.saveAndFlush(
        pendingInvitationBuilder()
            .recipientEmail(recipientEmail)
            .householdId(firstIssuer.household().getId())
            .householdName(firstIssuer.household().getName())
            .issuerAccountId(firstIssuer.account().getId())
            .publicId(issued.publicId())
            .secretDigest(issued.digest())
            .build());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var issuanceLock = holdInvitationIssuanceLock(recipientEmail)) {
      var replacement =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitation(
                      authTestSupport.identityOf(firstIssuer), invitationCommand(recipientEmail)));
      var blockerPid = backendPid(issuanceLock);
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(waitingBehind(blockerPid, RECIPIENT_LOCK)).isOne());

      var acceptance = executor.submit(() -> acceptIfPending(issued.code()));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> acceptance.isDone() || waitingBehind(blockerPid, RECIPIENT_LOCK) == 2);
      issuanceLock.rollback();

      var replacementSucceeded =
          replacement.get(10, TimeUnit.SECONDS) instanceof Outcome.Accepted<?, ?>;
      var acceptanceSucceeded = acceptance.get(10, TimeUnit.SECONDS);
      assertThat(acceptanceSucceeded && replacementSucceeded).isFalse();
    }

    var accountExists = userAccountRepository.findByEmailIgnoreCase(recipientEmail).isPresent();
    var pendingInvitationExists =
        invitationRepository.findAll().stream()
            .anyMatch(
                invitation ->
                    invitation.getRecipientEmail().equalsIgnoreCase(recipientEmail)
                        && invitation.getStatus() == AccountInvitationStatus.PENDING);
    assertThat(accountExists && pendingInvitationExists).isFalse();
  }

  @Test
  @DisplayName("Should expire an invitation that ages out while replacement waits for its lock")
  void shouldExpireInvitationThatAgesOutWhileReplacementWaitsForItsLock() throws Exception {
    firstIssuer = authTestSupport.createAdminIdentity();
    var recipientEmail = "expiry-during-invitation-replacement@example.com";
    var expiresAt = Instant.now().plusSeconds(2);
    var existing = saveBlockingInvitation(recipientEmail);
    existing.setExpiresAt(expiresAt);
    invitationRepository.saveAndFlush(existing);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var issuanceLock = holdInvitationIssuanceLock(recipientEmail)) {
      var replacement =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitation(
                      authTestSupport.identityOf(firstIssuer), invitationCommand(recipientEmail)));
      var blockerPid = backendPid(issuanceLock);
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(waitingBehind(blockerPid, RECIPIENT_LOCK)).isOne());
      assertThat(Instant.now()).isBefore(expiresAt);
      await().atMost(Duration.ofSeconds(3)).until(() -> !Instant.now().isBefore(expiresAt));
      issuanceLock.rollback();

      accepted(replacement.get(10, TimeUnit.SECONDS));
    }

    assertThat(invitationRepository.findById(existing.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.EXPIRED);
  }

  @Test
  @DisplayName("Should expire a reset code that ages out while replacement waits for its lock")
  void shouldExpireResetCodeThatAgesOutWhileReplacementWaitsForItsLock() throws Exception {
    firstIssuer = authTestSupport.createAdminIdentity();
    resetTarget = authTestSupport.createIdentity();
    var expiresAt = Instant.now().plusSeconds(2);
    var existing = saveBlockingResetCode();
    existing.setExpiresAt(expiresAt);
    resetCodeRepository.saveAndFlush(existing);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var lock = lockRow(rowLock("user_account", resetTarget.account().getId()))) {
      var replacement =
          executor.submit(
              () ->
                  credentialIssuanceService.issuePasswordReset(
                      authTestSupport.freshIdentityOf(firstIssuer),
                      resetTarget.account().getId(),
                      "recover access"));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(waitingBehind(lock, ACCOUNT_LOCK)).isOne());
      assertThat(Instant.now()).isBefore(expiresAt);
      await().atMost(Duration.ofSeconds(3)).until(() -> !Instant.now().isBefore(expiresAt));
      lock.release();

      accepted(replacement.get(10, TimeUnit.SECONDS));
    }

    assertThat(resetCodeRepository.findById(existing.getId()).orElseThrow().getStatus())
        .isEqualTo(PasswordResetCodeStatus.EXPIRED);
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    invitationRepository.deleteAll();
    resetCodeRepository.deleteAll();
    if (firstIssuer != null) {
      authTestSupport.deleteIdentity(firstIssuer);
    }

    if (secondIssuer != null) {
      authTestSupport.deleteIdentity(secondIssuer);
    }

    if (resetTarget != null) {
      authTestSupport.deleteIdentity(resetTarget);
    }
  }

  @Test
  @DisplayName(
      "Should leave the newest invitation pending when two issuers replace one recipient concurrently")
  void shouldLeaveNewestInvitationPendingWhenTwoIssuersReplaceOneRecipientConcurrently()
      throws Exception {
    firstIssuer = authTestSupport.createAdminIdentity();
    secondIssuer = authTestSupport.createAdminIdentity();
    var recipientEmail = "replacement-race@example.com";
    var blocker = saveBlockingInvitation(recipientEmail);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var lock = lockRow(rowLock("account_invitation", blocker.getId()))) {
      var first =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitation(
                      authTestSupport.identityOf(firstIssuer), invitationCommand(recipientEmail)));
      var second =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitation(
                      authTestSupport.identityOf(secondIssuer), invitationCommand(recipientEmail)));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                assertThat(waitingBehind(lock, RECIPIENT_UPDATE)).isOne();
                assertThat(waitingBehind(lock, RECIPIENT_LOCK)).isOne();
              });
      lock.release();

      accepted(first.get(10, TimeUnit.SECONDS));
      accepted(second.get(10, TimeUnit.SECONDS));
    }

    assertThat(invitationRepository.findAll())
        .extracting(AccountInvitation::getStatus)
        .containsExactlyInAnyOrder(
            AccountInvitationStatus.INVALIDATED,
            AccountInvitationStatus.INVALIDATED,
            AccountInvitationStatus.PENDING);
  }

  @Test
  @DisplayName(
      "Should leave the newest reset code pending when two issuers replace one Account concurrently")
  void shouldLeaveNewestResetCodePendingWhenTwoIssuersReplaceOneAccountConcurrently()
      throws Exception {
    firstIssuer = authTestSupport.createAdminIdentity();
    secondIssuer = authTestSupport.createAdminIdentity();
    resetTarget = authTestSupport.createIdentity();
    var blocker = saveBlockingResetCode();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var lock = lockRow(rowLock("password_reset_code", blocker.getId()))) {
      var first =
          executor.submit(
              () ->
                  credentialIssuanceService.issuePasswordReset(
                      authTestSupport.freshIdentityOf(firstIssuer),
                      resetTarget.account().getId(),
                      "recover access"));
      var second =
          executor.submit(
              () ->
                  credentialIssuanceService.issuePasswordReset(
                      authTestSupport.freshIdentityOf(secondIssuer),
                      resetTarget.account().getId(),
                      "recover access"));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                assertThat(waitingBehind(lock, RESET_CODE_UPDATE)).isOne();
                assertThat(waitingBehind(lock, ACCOUNT_LOCK)).isOne();
              });
      lock.release();

      accepted(first.get(10, TimeUnit.SECONDS));
      accepted(second.get(10, TimeUnit.SECONDS));
    }

    assertThat(resetCodeRepository.findAll())
        .extracting(PasswordResetCode::getStatus)
        .containsExactlyInAnyOrder(
            PasswordResetCodeStatus.INVALIDATED,
            PasswordResetCodeStatus.INVALIDATED,
            PasswordResetCodeStatus.PENDING);
  }

  private AccountInvitation saveBlockingInvitation(String recipientEmail) {
    return invitationRepository.saveAndFlush(
        pendingInvitationBuilder()
            .recipientEmail(recipientEmail)
            .householdId(firstIssuer.household().getId())
            .householdName(firstIssuer.household().getName())
            .issuerAccountId(firstIssuer.account().getId())
            .build());
  }

  private PasswordResetCode saveBlockingResetCode() {
    return resetCodeRepository.saveAndFlush(
        pendingResetCodeBuilder()
            .accountId(resetTarget.account().getId())
            .issuerAccountId(firstIssuer.account().getId())
            .build());
  }

  private IssueInvitationCommand invitationCommand(String recipientEmail) {
    return IssueInvitationCommand.builder()
        .recipientEmail(recipientEmail)
        .householdId(firstIssuer.household().getId())
        .householdRole(HouseholdRole.MEMBER)
        .profileName("Replacement")
        .profileKind(ProfileKind.ADULT)
        .build();
  }

  private RowLockTarget rowLock(String table, UUID rowId) {
    return RowLockTarget.builder().dataSource(dataSource).table(table).rowId(rowId).build();
  }

  private int waitingBehind(HeldRowLock lock, String queryPattern) {
    return waitersBehind(jdbcTemplate, lock.backendPid(), queryPattern);
  }

  private int waitingBehind(int blockerPid, String queryPattern) {
    return waitersBehind(jdbcTemplate, blockerPid, queryPattern);
  }

  private Connection holdInvitationIssuanceLock(String recipientEmail) throws SQLException {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement(
            """
            SELECT pg_advisory_xact_lock(
                hashtextextended('account-invitation:' || lower(?), 0))
            """)) {
      statement.setString(1, recipientEmail);
      statement.executeQuery().close();
    } catch (SQLException failure) {
      connection.close();
      throw failure;
    }

    return connection;
  }

  private boolean acceptIfPending(String rawCode) {
    try {
      accountInvitationService.accept(
          AcceptInvitationCommand.builder()
              .code(rawCode)
              .displayName("Invitee")
              .password("a strong passphrase")
              .deviceName("test")
              .build());
      return true;
    } catch (InvalidOneTimeCodeException _) {
      return false;
    }
  }
}
