package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.support.PostgresLockTestSupport.lockNormalizedKey;
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
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccountInvitationCeremonyService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.PasswordResetRedemptionService;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Tag("IntegrationTest")
@DisplayName("Credential Issuance Revocation Race Integration Tests")
class CredentialIssuanceRevocationRaceIT extends AbstractIntegrationTest {

  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountAdministrationService accountAdministrationService;
  @Autowired private AccountInvitationCeremonyService invitationCeremonyService;
  @Autowired private PasswordResetRedemptionService resetRedemptionService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;

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
    var rowLocked = new CountDownLatch(1);
    var releaseRow = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lock =
          executor.submit(() -> holdResetCodeRowLock(blocker.getId(), rowLocked, releaseRow));
      assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

      var issuance =
          executor.submit(
              () ->
                  credentialIssuanceService.issuePasswordReset(
                      freshIdentityOf(issuer), issuer.account().getId(), "recover access"));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(hasWaitingResetCodeUpdate()).isTrue());

      var revocation =
          executor.submit(
              () ->
                  accountAdministrationService.revokeServerAdmin(
                      freshIdentityOf(revoker),
                      issuer.account().getId(),
                      "authority no longer required"));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> assertThat(revocation.isDone() || hasWaitingAccountUpdate()).isTrue());

      releaseRow.countDown();
      var issued = acceptedResetCode(issuance.get(10, TimeUnit.SECONDS));
      assertThat(revocation.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      lock.get(10, TimeUnit.SECONDS);

      assertThat(
              userAccountRepository
                  .findById(issuer.account().getId())
                  .orElseThrow()
                  .isServerAdmin())
          .isFalse();
      assertThat(resetCodeRepository.findById(issued.resetCode().getId()).orElseThrow().getStatus())
          .isEqualTo(PasswordResetCodeStatus.INVALIDATED);
      assertThatThrownBy(
              () -> resetRedemptionService.redeem(issued.code(), "a replacement passphrase"))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    } finally {
      releaseRow.countDown();
    }
  }

  @Test
  @DisplayName("Should leave no usable invitation when its issuer is disabled during issuance")
  void shouldLeaveNoUsableInvitationWhenIssuerIsDisabledDuringIssuance() throws Exception {
    issuer = authTestSupport.createAdminIdentity();
    revoker = authTestSupport.createAdminIdentity();
    var recipientEmail = "issuance-race@example.com";
    var blocker = saveBlockingInvitation(recipientEmail);
    var rowLocked = new CountDownLatch(1);
    var releaseRow = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lock =
          executor.submit(() -> holdInvitationRowLock(blocker.getId(), rowLocked, releaseRow));
      assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

      var issuance =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitation(
                      identityOf(issuer), invitationCommand(recipientEmail)));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(hasWaitingInvitationUpdate()).isTrue());

      var revocationStarted = new CountDownLatch(1);
      var disable =
          executor.submit(
              () -> {
                revocationStarted.countDown();
                return accountAdministrationService.disableAccount(
                    identityOf(revoker), issuer.account().getId());
              });
      assertThat(revocationStarted.await(10, TimeUnit.SECONDS)).isTrue();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(disable.isDone() || hasWaitingAccountUpdate()).isTrue());

      releaseRow.countDown();
      var issued = acceptedInvitation(issuance.get(10, TimeUnit.SECONDS));
      assertThat(disable.get(10, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      lock.get(10, TimeUnit.SECONDS);

      assertThat(userAccountRepository.findById(issuer.account().getId()).orElseThrow().isEnabled())
          .isFalse();
      assertThat(
              invitationRepository.findById(issued.invitation().getId()).orElseThrow().getStatus())
          .isEqualTo(AccountInvitationStatus.INVALIDATED);
      assertThatThrownBy(() -> invitationCeremonyService.lookup(issued.code()))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    } finally {
      releaseRow.countDown();
    }
  }

  @Test
  @DisplayName("Should let issuer disable complete while invitation waits for its recipient lock")
  void shouldLetIssuerDisableCompleteWhileInvitationWaitsForRecipientLock() throws Exception {
    issuer = authTestSupport.createAdminIdentity();
    revoker = authTestSupport.createAdminIdentity();
    var recipientEmail = "issuance-lock-order@example.com";

    try (var lockConnection = dataSource.getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      lockConnection.setAutoCommit(false);
      lockNormalizedKey(lockConnection, "account-invitation", recipientEmail);

      var issuance =
          executor.submit(
              () ->
                  credentialIssuanceService.issueAccountInvitation(
                      identityOf(issuer), invitationCommand(recipientEmail)));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(hasWaitingCredentialLock()).isTrue());

      try {
        var disable =
            executor.submit(
                () ->
                    accountAdministrationService.disableAccount(
                        identityOf(revoker), issuer.account().getId()));

        assertThat(disable.get(2, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
        assertThat(issuance.isDone()).isFalse();
      } finally {
        lockConnection.rollback();
      }

      assertThatThrownBy(() -> issuance.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AccessDeniedException.class);
    }

    assertThat(invitationRepository.findAll()).isEmpty();
  }

  private AccountInvitation saveBlockingInvitation(String recipientEmail) {
    return invitationRepository.saveAndFlush(
        AccountInvitation.builder()
            .recipientEmail(recipientEmail)
            .householdId(issuer.household().getId())
            .householdName(issuer.household().getName())
            .householdRole(HouseholdRole.MEMBER)
            .profileName("Earlier")
            .profileKind(ProfileKind.ADULT)
            .issuerAccountId(revoker.account().getId())
            .expiresAt(Instant.now().plus(Duration.ofDays(1)))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build());
  }

  private PasswordResetCode saveBlockingResetCode() {
    return resetCodeRepository.saveAndFlush(
        PasswordResetCode.builder()
            .accountId(issuer.account().getId())
            .issuerAccountId(revoker.account().getId())
            .expiresAt(Instant.now().plus(Duration.ofDays(1)))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
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
        throw new AssertionError("issuance did not release the invitation row lock");
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
        throw new AssertionError("issuance did not release the reset-code row lock");
      }
      connection.rollback();
    } catch (Exception exception) {
      throw new AssertionError("could not coordinate the reset-code row lock", exception);
    }
  }

  private boolean hasWaitingCredentialLock() {
    var waiting =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE wait_event_type = 'Lock'
                AND query ILIKE '%pg_advisory_xact_lock%'
            )
            """,
            Boolean.class);
    return Boolean.TRUE.equals(waiting);
  }

  private boolean hasWaitingInvitationUpdate() {
    var waiting =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE wait_event_type = 'Lock'
                AND query ILIKE '%update%account_invitation%'
                AND query ILIKE '%recipient_email%'
            )
            """,
            Boolean.class);
    return Boolean.TRUE.equals(waiting);
  }

  private boolean hasWaitingAccountUpdate() {
    var waiting =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE wait_event_type = 'Lock'
                AND query ILIKE '%update%user_account%'
            )
            """,
            Boolean.class);
    return Boolean.TRUE.equals(waiting);
  }

  private boolean hasWaitingResetCodeUpdate() {
    var waiting =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE wait_event_type = 'Lock'
                AND query ILIKE '%update%password_reset_code%'
                AND query ILIKE '%account_id%'
            )
            """,
            Boolean.class);
    return Boolean.TRUE.equals(waiting);
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
