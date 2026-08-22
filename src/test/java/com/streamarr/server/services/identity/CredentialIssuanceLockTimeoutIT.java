package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.support.PostgresLockTestSupport.lockNormalizedKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Tag("IntegrationTest")
@DisplayName("Credential Issuance Lock Timeout Integration Tests")
@SpringBootTest(properties = "auth.credential-codes.replacement-lock-timeout=200ms")
class CredentialIssuanceLockTimeoutIT extends AbstractIntegrationTest {

  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;

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

    assertReplacementTimesOut(
        "account-invitation",
        recipientEmail,
        () ->
            credentialIssuanceService.issueAccountInvitation(
                identityOf(issuer), invitationCommand(recipientEmail)));

    assertThat(invitationRepository.findAll())
        .singleElement()
        .satisfies(
            invitation -> {
              assertThat(invitation.getId()).isEqualTo(existing.getId());
              assertThat(invitation.getStatus()).isEqualTo(AccountInvitationStatus.PENDING);
            });
  }

  @Test
  @DisplayName("Should preserve existing reset code when replacement exceeds lock timeout")
  void shouldPreserveExistingResetCodeWhenReplacementExceedsLockTimeout() throws Exception {
    issuer = authTestSupport.createAdminIdentity();
    resetTarget = authTestSupport.createIdentity();
    var accountId = resetTarget.account().getId();
    var existing = savePendingResetCode();

    assertReplacementTimesOut(
        "password-reset",
        accountId.toString(),
        () ->
            credentialIssuanceService.issuePasswordReset(
                freshIdentityOf(issuer), accountId, "recover access"));

    assertThat(resetCodeRepository.findAll())
        .singleElement()
        .satisfies(
            resetCode -> {
              assertThat(resetCode.getId()).isEqualTo(existing.getId());
              assertThat(resetCode.getStatus()).isEqualTo(PasswordResetCodeStatus.PENDING);
            });
  }

  private AccountInvitation savePendingInvitation(String recipientEmail) {
    return invitationRepository.saveAndFlush(
        AccountInvitation.builder()
            .recipientEmail(recipientEmail)
            .householdId(issuer.household().getId())
            .householdName(issuer.household().getName())
            .householdRole(HouseholdRole.MEMBER)
            .profileName("Earlier")
            .profileKind(ProfileKind.ADULT)
            .issuerAccountId(issuer.account().getId())
            .expiresAt(Instant.now().plus(Duration.ofDays(1)))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build());
  }

  private PasswordResetCode savePendingResetCode() {
    return resetCodeRepository.saveAndFlush(
        PasswordResetCode.builder()
            .accountId(resetTarget.account().getId())
            .issuerAccountId(issuer.account().getId())
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

  private void assertReplacementTimesOut(String namespace, String value, Supplier<?> replacement)
      throws Exception {
    try (var lockConnection = dataSource.getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      lockConnection.setAutoCommit(false);
      lockNormalizedKey(lockConnection, namespace, value);

      var contender = executor.submit(replacement::get);
      try {
        assertThatThrownBy(() -> contender.get(2, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasStackTraceContaining("canceling statement due to lock timeout");
      } finally {
        lockConnection.rollback();
      }
    }
  }
}
