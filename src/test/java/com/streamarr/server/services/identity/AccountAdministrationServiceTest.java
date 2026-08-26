package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakePasswordResetCodeRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Account Administration Service Tests")
class AccountAdministrationServiceTest {

  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final FakeAccountInvitationRepository invitations = new FakeAccountInvitationRepository();
  private final FakePasswordResetCodeRepository resetCodes = new FakePasswordResetCodeRepository();

  private final AccountAdministrationService service =
      new AccountAdministrationService(
          authorization,
          accounts,
          sessions,
          audit,
          invitations,
          resetCodes,
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()),
          Clock.systemUTC());

  private UserAccount target;

  @BeforeEach
  void setUp() {
    target = accounts.save(AccountFixture.defaultAccountBuilder().build());
  }

  @Test
  @DisplayName("Should grant server admin and audit once when the transition wins")
  void shouldGrantServerAdminAndAuditOnceWhenTransitionWins() {
    var outcome = service.grantServerAdmin(identity(), target.getId(), "onboarding");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().isServerAdmin()).isTrue();
    assertThat(audit.entries()).hasSize(1);
    var entry = audit.entries().getFirst();
    assertThat(entry.operation()).isEqualTo("grantServerAdmin");
    assertThat(entry.reason()).isEqualTo("onboarding");
    assertThat(entry.resources()).containsEntry("accountId", target.getId());
  }

  @Test
  @DisplayName("Should not audit again when the Account is already in the target state")
  void shouldNotAuditAgainWhenAccountAlreadyInTargetState() {
    service.grantServerAdmin(identity(), target.getId(), "first");

    var outcome = service.grantServerAdmin(identity(), target.getId(), "again");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(audit.entries()).hasSize(1);
  }

  @Test
  @DisplayName("Should avoid authorization when the grant reason is missing")
  void shouldAvoidAuthorizationWhenGrantReasonMissing() {
    var outcome = service.grantServerAdmin(identity(), target.getId(), "  ");

    assertThat(rejectionOf(outcome)).isInstanceOf(AdministrationRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should require a reason when the grant reason is null")
  void shouldRequireReasonWhenGrantReasonIsNull() {
    var outcome = service.grantServerAdmin(identity(), target.getId(), null);

    assertThat(rejectionOf(outcome)).isInstanceOf(AdministrationRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should report the missing ceremony when reauthentication is all that is missing")
  void shouldReportMissingCeremonyWhenReauthenticationIsAllThatIsMissing() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.GrantServerAdmin
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());

    var outcome = service.grantServerAdmin(identity(), target.getId(), "onboarding");

    assertThat(rejectionOf(outcome))
        .isInstanceOf(AdministrationRejections.ReauthenticationRequired.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().isServerAdmin()).isFalse();
  }

  @Test
  @DisplayName("Should throw forbidden when the denied caller may view the Account")
  void shouldThrowForbiddenWhenDeniedCallerMayViewAccount() {
    var identity = identity();
    var accountId = target.getId();
    authorization.decideUnitWith(
        intent -> intent instanceof Intent.GrantServerAdmin ? denied() : allowed());

    assertThatThrownBy(() -> service.grantServerAdmin(identity, accountId, "onboarding"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should read as not found when the denied caller may not view the Account")
  void shouldReadAsNotFoundWhenDeniedCallerMayNotViewAccount() {
    authorization.denyAll();

    var outcome = service.grantServerAdmin(identity(), target.getId(), "onboarding");

    assertThat(rejectionOf(outcome)).isInstanceOf(AdministrationRejections.AccountNotFound.class);
  }

  @Test
  @DisplayName("Should fail closed when no decision could be made")
  void shouldFailClosedWhenNoDecisionCouldBeMade() {
    var identity = identity();
    var accountId = target.getId();
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);

    assertThatThrownBy(() -> service.grantServerAdmin(identity, accountId, "onboarding"))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when Account visibility cannot be decided")
  void shouldFailClosedWhenAccountVisibilityCannotBeDecided() {
    var identity = identity();
    var accountId = target.getId();
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.GrantServerAdmin
                ? denied()
                : new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE));

    assertThatThrownBy(() -> service.grantServerAdmin(identity, accountId, "onboarding"))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should return not found when the Account is unknown to an allowed caller")
  void shouldReturnNotFoundWhenAccountUnknownToAllowedCaller() {
    var outcome = service.grantServerAdmin(identity(), UUID.randomUUID(), "onboarding");

    assertThat(rejectionOf(outcome)).isInstanceOf(AdministrationRejections.AccountNotFound.class);
  }

  @Test
  @DisplayName("Should revoke every live session when the Account is disabled")
  void shouldRevokeEveryLiveSessionWhenAccountIsDisabled() {
    var browser =
        sessions.save(AuthSession.builder().accountId(target.getId()).deviceName("web").build());
    var television =
        sessions.save(AuthSession.builder().accountId(target.getId()).deviceName("tv").build());
    var alreadyRevokedAt = Instant.parse("2026-08-20T12:00:00Z");
    var alreadyRevoked =
        sessions.save(
            AuthSession.builder()
                .accountId(target.getId())
                .deviceName("old phone")
                .revokedAt(alreadyRevokedAt)
                .revokedReason(SessionRevocationReason.LOGOUT)
                .build());

    var outcome = service.disableAccount(identity(), target.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().isEnabled()).isFalse();
    assertThat(List.of(browser, television))
        .isNotEmpty()
        .allSatisfy(
            session -> {
              var revoked = sessions.findById(session.getId()).orElseThrow();
              assertThat(revoked.getRevokedAt()).isNotNull();
              assertThat(revoked.getRevokedReason())
                  .isEqualTo(SessionRevocationReason.ADMIN_REVOCATION);
            });
    assertThat(sessions.findById(alreadyRevoked.getId()).orElseThrow())
        .satisfies(
            session -> {
              assertThat(session.getRevokedAt()).isEqualTo(alreadyRevokedAt);
              assertThat(session.getRevokedReason()).isEqualTo(SessionRevocationReason.LOGOUT);
            });
    assertThat(audit.entries())
        .extracting(entry -> entry.operation())
        .containsExactly("disableAccount");
  }

  @Test
  @DisplayName("Should invalidate outstanding credentials when the issuer is disabled")
  void shouldInvalidateOutstandingCredentialsWhenIssuerIsDisabled() {
    savePendingCredentialsIssuedBy(target.getId());

    service.disableAccount(identity(), target.getId());

    assertThat(invitations.findAll())
        .singleElement()
        .extracting(AccountInvitation::getStatus)
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(resetCodes.findAll())
        .singleElement()
        .extracting(PasswordResetCode::getStatus)
        .isEqualTo(PasswordResetCodeStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should leave expired credentials expired when the issuer is disabled")
  void shouldLeaveExpiredCredentialsExpiredWhenIssuerIsDisabled() {
    savePendingCredentialsIssuedBy(target.getId());
    var now = Instant.now();
    invitations.findAll().getFirst().setExpiresAt(now.minusSeconds(1));
    resetCodes.findAll().getFirst().setExpiresAt(now.minusSeconds(1));

    service.disableAccount(identity(), target.getId());

    assertThat(invitations.findAll())
        .singleElement()
        .satisfies(
            invitation -> {
              assertThat(invitation.statusAt(now)).isEqualTo(AccountInvitationStatus.EXPIRED);
              assertThat(invitation.getInvalidationReason()).isNull();
            });
    assertThat(resetCodes.findAll())
        .singleElement()
        .satisfies(
            code -> {
              assertThat(code.statusAt(now)).isEqualTo(PasswordResetCodeStatus.EXPIRED);
              assertThat(code.getInvalidationReason()).isNull();
            });
  }

  @Test
  @DisplayName("Should invalidate outstanding credentials when the issuer loses ServerAdmin")
  void shouldInvalidateOutstandingCredentialsWhenIssuerLosesServerAdmin() {
    target.setServerAdmin(true);
    savePendingCredentialsIssuedBy(target.getId());

    service.revokeServerAdmin(identity(), target.getId(), "rotation");

    assertThat(invitations.findAll())
        .singleElement()
        .extracting(AccountInvitation::getStatus)
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(resetCodes.findAll())
        .singleElement()
        .extracting(PasswordResetCode::getStatus)
        .isEqualTo(PasswordResetCodeStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should enable an Account and audit when the transition wins")
  void shouldEnableAccountAndAuditWhenTransitionWins() {
    target.setEnabled(false);
    accounts.save(target);

    assertThat(service.enableAccount(identity(), target.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().isEnabled()).isTrue();
    assertThat(audit.entries())
        .extracting(entry -> entry.operation())
        .containsExactly("enableAccount");
  }

  @Test
  @DisplayName("Should grant HouseholdAdmin and audit when the transition wins")
  void shouldGrantHouseholdAdminAndAuditWhenTransitionWins() {
    target.setHouseholdRole(HouseholdRole.MEMBER);
    assertThat(service.grantHouseholdAdmin(identity(), target.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().getHouseholdRole())
        .isEqualTo(HouseholdRole.ADMIN);
    assertThat(audit.entries())
        .extracting(entry -> entry.operation())
        .containsExactly("grantHouseholdAdmin");
  }

  @Test
  @DisplayName("Should revoke HouseholdAdmin and audit when the transition wins")
  void shouldRevokeHouseholdAdminAndAuditWhenTransitionWins() {
    target.setHouseholdRole(HouseholdRole.ADMIN);
    assertThat(service.revokeHouseholdAdmin(identity(), target.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().getHouseholdRole())
        .isEqualTo(HouseholdRole.MEMBER);
    assertThat(audit.entries())
        .extracting(entry -> entry.operation())
        .containsExactly("revokeHouseholdAdmin");
  }

  @Test
  @DisplayName("Should rename an Account without an authority audit when allowed")
  void shouldRenameAccountWithoutAuthorityAuditWhenAllowed() {
    assertThat(service.renameAccount(identity(), target.getId(), "  New Name  "))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().getDisplayName())
        .isEqualTo("New Name");
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should revoke server admin and audit when the transition wins")
  void shouldRevokeServerAdminAndAuditWhenTransitionWins() {
    target.setServerAdmin(true);
    accounts.save(target);

    var outcome = service.revokeServerAdmin(identity(), target.getId(), "rotation");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().isServerAdmin()).isFalse();
    assertThat(audit.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.operation()).isEqualTo("revokeServerAdmin");
              assertThat(entry.reason()).isEqualTo("rotation");
            });
  }

  @Test
  @DisplayName("Should require a reason when revoking server admin")
  void shouldRequireReasonWhenRevokingServerAdmin() {
    target.setServerAdmin(true);
    accounts.save(target);

    var outcome = service.revokeServerAdmin(identity(), target.getId(), "  ");

    assertThat(rejectionOf(outcome)).isInstanceOf(AdministrationRejections.ReasonRequired.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().isServerAdmin()).isTrue();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should require a reason when the revocation reason is null")
  void shouldRequireReasonWhenRevocationReasonIsNull() {
    target.setServerAdmin(true);
    accounts.save(target);

    var outcome = service.revokeServerAdmin(identity(), target.getId(), null);

    assertThat(rejectionOf(outcome)).isInstanceOf(AdministrationRejections.ReasonRequired.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().isServerAdmin()).isTrue();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should require a display name when renaming")
  void shouldRequireDisplayNameWhenRenaming() {
    var outcome = service.renameAccount(identity(), target.getId(), " ");

    assertThat(rejectionOf(outcome))
        .isInstanceOf(AdministrationRejections.DisplayNameRequired.class);
  }

  @Test
  @DisplayName("Should require a display name when the rename value is null")
  void shouldRequireDisplayNameWhenRenameValueIsNull() {
    var outcome = service.renameAccount(identity(), target.getId(), null);

    assertThat(rejectionOf(outcome))
        .isInstanceOf(AdministrationRejections.DisplayNameRequired.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().getDisplayName())
        .isEqualTo(target.getDisplayName());
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  private void savePendingCredentialsIssuedBy(UUID issuerAccountId) {
    var expiresAt = Instant.now().plus(Duration.ofHours(1));
    invitations.save(
        AccountInvitation.builder()
            .recipientEmail("invitee@example.com")
            .householdName("Home")
            .householdRole(HouseholdRole.MEMBER)
            .profileName("Invitee")
            .profileKind(ProfileKind.ADULT)
            .issuerAccountId(issuerAccountId)
            .expiresAt(expiresAt)
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build());
    resetCodes.save(
        PasswordResetCode.builder()
            .accountId(target.getId())
            .issuerAccountId(issuerAccountId)
            .expiresAt(expiresAt)
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build());
  }

  private static Object rejectionOf(Outcome<?, ?> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, ?>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, ?> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }

  private static Decision<AuthorizationUnit> allowed() {
    return new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  }

  private static Decision<AuthorizationUnit> denied() {
    return new Decision.Denied<>(Decision.DenialReason.POLICY);
  }
}
