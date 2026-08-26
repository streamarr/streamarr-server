package com.streamarr.server.services.identity;

import static com.streamarr.server.fixtures.AccountInvitationFixture.pendingInvitationBuilder;
import static com.streamarr.server.fixtures.PasswordResetCodeFixture.pendingResetCodeBuilder;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.time.Duration;
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

  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity firstIssuer;
  private AuthTestSupport.TestIdentity secondIssuer;
  private AuthTestSupport.TestIdentity resetTarget;

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
                assertThat(waitingInvitationUpdates()).isOne();
                assertThat(waitingRecipientLocks()).isOne();
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
                assertThat(waitingResetCodeUpdates()).isOne();
                assertThat(waitingAccountLocks()).isOne();
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

  private int waitingInvitationUpdates() {
    return jdbcTemplate.queryForObject(
        """
        SELECT count(*)
        FROM pg_stat_activity
        WHERE wait_event_type = 'Lock'
          AND query ILIKE '%update%account_invitation%'
          AND query ILIKE '%recipient_email%'
        """,
        Integer.class);
  }

  private int waitingRecipientLocks() {
    return jdbcTemplate.queryForObject(
        """
        SELECT count(*)
        FROM pg_stat_activity
        WHERE wait_event_type = 'Lock'
          AND query ILIKE '%pg_advisory_xact_lock%'
        """,
        Integer.class);
  }

  private int waitingResetCodeUpdates() {
    return jdbcTemplate.queryForObject(
        """
        SELECT count(*)
        FROM pg_stat_activity
        WHERE wait_event_type = 'Lock'
          AND query ILIKE '%update%password_reset_code%'
          AND query ILIKE '%account_id%'
        """,
        Integer.class);
  }

  private int waitingAccountLocks() {
    return jdbcTemplate.queryForObject(
        """
        SELECT count(*)
        FROM pg_stat_activity
        WHERE wait_event_type = 'Lock'
          AND query ILIKE '%user_account%'
          AND query ILIKE '%for update%'
        """,
        Integer.class);
  }
}
