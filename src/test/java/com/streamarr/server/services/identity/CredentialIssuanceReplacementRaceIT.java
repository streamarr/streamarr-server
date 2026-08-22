package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
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
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Tag("IntegrationTest")
@DisplayName("Credential Issuance Replacement Race Integration Tests")
class CredentialIssuanceReplacementRaceIT extends AbstractIntegrationTest {

  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;
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
    var rowLocked = new CountDownLatch(1);
    var releaseRow = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lock =
          executor.submit(() -> holdInvitationRowLock(blocker.getId(), rowLocked, releaseRow));
      assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

      var first =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitation(
                      identityOf(firstIssuer), invitationCommand(recipientEmail)));
      var second =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitation(
                      identityOf(secondIssuer), invitationCommand(recipientEmail)));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                assertThat(waitingInvitationUpdates()).isOne();
                assertThat(waitingRecipientLocks()).isOne();
              });
      releaseRow.countDown();

      acceptedInvitation(first.get(10, TimeUnit.SECONDS));
      acceptedInvitation(second.get(10, TimeUnit.SECONDS));
      lock.get(10, TimeUnit.SECONDS);
    } finally {
      releaseRow.countDown();
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
    var rowLocked = new CountDownLatch(1);
    var releaseRow = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lock =
          executor.submit(() -> holdResetCodeRowLock(blocker.getId(), rowLocked, releaseRow));
      assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

      var first =
          executor.submit(
              () ->
                  credentialIssuanceService.issuePasswordReset(
                      freshIdentityOf(firstIssuer),
                      resetTarget.account().getId(),
                      "recover access"));
      var second =
          executor.submit(
              () ->
                  credentialIssuanceService.issuePasswordReset(
                      freshIdentityOf(secondIssuer),
                      resetTarget.account().getId(),
                      "recover access"));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                assertThat(waitingResetCodeUpdates()).isOne();
                assertThat(waitingRecipientLocks()).isOne();
              });
      releaseRow.countDown();

      acceptedResetCode(first.get(10, TimeUnit.SECONDS));
      acceptedResetCode(second.get(10, TimeUnit.SECONDS));
      lock.get(10, TimeUnit.SECONDS);
    } finally {
      releaseRow.countDown();
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
        AccountInvitation.builder()
            .recipientEmail(recipientEmail)
            .householdId(firstIssuer.household().getId())
            .householdName(firstIssuer.household().getName())
            .householdRole(HouseholdRole.MEMBER)
            .profileName("Earlier")
            .profileKind(ProfileKind.ADULT)
            .issuerAccountId(firstIssuer.account().getId())
            .expiresAt(Instant.now().plus(Duration.ofDays(1)))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build());
  }

  private PasswordResetCode saveBlockingResetCode() {
    return resetCodeRepository.saveAndFlush(
        PasswordResetCode.builder()
            .accountId(resetTarget.account().getId())
            .issuerAccountId(firstIssuer.account().getId())
            .expiresAt(Instant.now().plus(Duration.ofDays(1)))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
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

  private AuthenticatedIdentity identityOf(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.fromJwt(
        jwtDecoder.decode(authTestSupport.accountBearer(identity)));
  }

  private AuthenticatedIdentity freshIdentityOf(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.fromJwt(
        jwtDecoder.decode(authTestSupport.freshAccountBearer(identity)));
  }

  private void holdInvitationRowLock(
      UUID invitationId, CountDownLatch rowLocked, CountDownLatch releaseRow) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT id FROM account_invitation WHERE id = ? FOR UPDATE")) {
      connection.setAutoCommit(false);
      statement.setObject(1, invitationId);
      statement.executeQuery();
      rowLocked.countDown();
      if (!releaseRow.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("test did not release the invitation row lock");
      }

      connection.rollback();
    } catch (Exception exception) {
      throw new AssertionError("could not coordinate the invitation row lock", exception);
    }
  }

  private void holdResetCodeRowLock(
      UUID resetCodeId, CountDownLatch rowLocked, CountDownLatch releaseRow) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT id FROM password_reset_code WHERE id = ? FOR UPDATE")) {
      connection.setAutoCommit(false);
      statement.setObject(1, resetCodeId);
      statement.executeQuery();
      rowLocked.countDown();
      if (!releaseRow.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("test did not release the reset-code row lock");
      }

      connection.rollback();
    } catch (Exception exception) {
      throw new AssertionError("could not coordinate the reset-code row lock", exception);
    }
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

  private static CredentialIssuanceService.IssuedInvitation acceptedInvitation(
      Outcome<CredentialIssuanceService.IssuedInvitation, ?> outcome) {
    return outcome.fold(
        value -> value,
        rejections -> {
          throw new AssertionError("expected acceptance but got " + rejections);
        });
  }

  private static CredentialIssuanceService.IssuedResetCode acceptedResetCode(
      Outcome<CredentialIssuanceService.IssuedResetCode, ?> outcome) {
    return outcome.fold(
        value -> value,
        rejections -> {
          throw new AssertionError("expected acceptance but got " + rejections);
        });
  }
}
