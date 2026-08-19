package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * The decision classification and audit rules of Account administration: an allowed transition
 * writes once and audits once; REAUTHENTICATION_REQUIRED reports the missing ceremony; a policy
 * denial is FORBIDDEN for a caller who may view the Account and not-found for one who may not.
 */
@Tag("UnitTest")
@DisplayName("Account Administration Service Tests")
class AccountAdministrationServiceTest {

  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final AccountAdministrationService service =
      new AccountAdministrationService(
          authorization,
          accounts,
          sessions,
          audit,
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()),
          Clock.systemUTC());

  private UserAccount target;

  @BeforeEach
  void setUp() {
    target = accounts.save(AccountFixture.defaultAccountBuilder().build());
  }

  @Test
  @DisplayName("Should grant server admin and audit the winning transition once")
  void shouldGrantServerAdminAndAuditWinningTransitionOnce() {
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
  @DisplayName("Should require a reason before any decision is made")
  void shouldRequireReasonBeforeAnyDecisionIsMade() {
    var outcome = service.grantServerAdmin(identity(), target.getId(), "  ");

    assertThat(rejectionOf(outcome)).isInstanceOf(AdministrationRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should report the missing ceremony when reauthentication is all that is missing")
  void shouldReportMissingCeremonyWhenReauthenticationIsAllThatIsMissing() {
    authorization.decideWith(
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
    authorization.decideWith(
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
  @DisplayName("Should read an unknown Account as not found even for an allowed caller")
  void shouldReadUnknownAccountAsNotFoundEvenForAllowedCaller() {
    var outcome = service.grantServerAdmin(identity(), UUID.randomUUID(), "onboarding");

    assertThat(rejectionOf(outcome)).isInstanceOf(AdministrationRejections.AccountNotFound.class);
  }

  @Test
  @DisplayName("Should revoke every live session when the Account is disabled")
  void shouldRevokeEveryLiveSessionWhenAccountIsDisabled() {
    var session =
        sessions.save(AuthSession.builder().accountId(target.getId()).deviceName("web").build());

    var outcome = service.disableAccount(identity(), target.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().isEnabled()).isFalse();
    var revoked = sessions.findById(session.getId()).orElseThrow();
    assertThat(revoked.getRevokedAt()).isNotNull();
    assertThat(revoked.getRevokedReason()).isEqualTo(SessionRevocationReason.ADMIN_REVOCATION);
    assertThat(audit.entries())
        .extracting(entry -> entry.operation())
        .containsExactly("disableAccount");
  }

  @Test
  @DisplayName("Should promote, demote, enable, and rename through their transitions")
  void shouldPromoteDemoteEnableAndRenameThroughTheirTransitions() {
    service.disableAccount(identity(), target.getId());
    assertThat(service.enableAccount(identity(), target.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().isEnabled()).isTrue();

    target.setHouseholdRole(HouseholdRole.MEMBER);
    assertThat(service.grantHouseholdAdmin(identity(), target.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().getHouseholdRole())
        .isEqualTo(HouseholdRole.ADMIN);

    assertThat(service.revokeHouseholdAdmin(identity(), target.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().getHouseholdRole())
        .isEqualTo(HouseholdRole.MEMBER);

    assertThat(service.renameAccount(identity(), target.getId(), "  New Name  "))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(target.getId()).orElseThrow().getDisplayName())
        .isEqualTo("New Name");
  }

  @Test
  @DisplayName("Should require a display name when renaming")
  void shouldRequireDisplayNameWhenRenaming() {
    var outcome = service.renameAccount(identity(), target.getId(), " ");

    assertThat(rejectionOf(outcome))
        .isInstanceOf(AdministrationRejections.DisplayNameRequired.class);
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  private static Object rejectionOf(Outcome<?, ?> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, ?>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, ?> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }

  private static Decision<?> allowed() {
    return new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  }

  private static Decision<?> denied() {
    return new Decision.Denied<>(Decision.DenialReason.POLICY);
  }
}
