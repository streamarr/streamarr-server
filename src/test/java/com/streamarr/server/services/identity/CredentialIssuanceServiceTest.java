package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakePasswordResetCodeRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.services.auth.OpaqueCodes;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
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
 * Issuing invitations and reset codes over fakes: whole-surface gating, the issue-time validations,
 * replacement invalidating the older pending artifact, and the reset issue's fresh-reauthentication
 * classification and audit.
 */
@Tag("UnitTest")
@DisplayName("Credential Issuance Service Tests")
class CredentialIssuanceServiceTest {

  private final FakeAccountInvitationRepository invitations = new FakeAccountInvitationRepository();
  private final FakePasswordResetCodeRepository resetCodes = new FakePasswordResetCodeRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final CredentialIssuanceService service =
      new CredentialIssuanceService(
          authorization,
          invitations,
          resetCodes,
          accounts,
          households,
          audit,
          new OpaqueCodes(),
          new CredentialCodeProperties(null, null),
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()),
          Clock.systemUTC());

  private Household household;
  private UserAccount resident;

  @BeforeEach
  void setUp() {
    household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    resident =
        accounts.save(
            AccountFixture.defaultAccountBuilder().householdId(household.getId()).build());
  }

  @Test
  @DisplayName("Should issue a code once and replace the older pending invitation")
  void shouldIssueCodeOnceAndReplaceOlderPendingInvitation() {
    var first = issued(service.issueAccountInvitation(authorization.currentIdentity(), command()));
    var second = issued(service.issueAccountInvitation(authorization.currentIdentity(), command()));

    assertThat(first.code()).contains(".").isNotEqualTo(second.code());
    assertThat(first.invitation().getSecretDigest()).isNotEmpty();
    var statuses = invitations.findAll().stream().map(AccountInvitation::getStatus).toList();
    assertThat(statuses)
        .containsExactlyInAnyOrder(
            AccountInvitationStatus.INVALIDATED, AccountInvitationStatus.PENDING);
  }

  @Test
  @DisplayName("Should validate the invitation before any write")
  void shouldValidateInvitationBeforeAnyWrite() {
    assertThat(
            rejectionOf(
                service.issueAccountInvitation(
                    authorization.currentIdentity(),
                    command().toBuilder().recipientEmail(" ").build())))
        .isInstanceOf(InvitationRejections.EmailRequired.class);
    assertThat(
            rejectionOf(
                service.issueAccountInvitation(
                    authorization.currentIdentity(),
                    command().toBuilder().recipientEmail(resident.getEmail()).build())))
        .isInstanceOf(InvitationRejections.EmailAlreadyUsed.class);
    assertThat(
            rejectionOf(
                service.issueAccountInvitation(
                    authorization.currentIdentity(),
                    command().toBuilder().householdId(UUID.randomUUID()).build())))
        .isInstanceOf(InvitationRejections.HouseholdNotFound.class);
    assertThat(
            rejectionOf(
                service.issueAccountInvitation(
                    authorization.currentIdentity(),
                    command().toBuilder().profileKind(ProfileKind.KID).build())))
        .isInstanceOf(InvitationRejections.LocalManagerRequired.class);
    var emptyHousehold = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    assertThat(
            rejectionOf(
                service.issueAccountInvitation(
                    authorization.currentIdentity(),
                    command().toBuilder()
                        .householdId(emptyHousehold.getId())
                        .profileKind(ProfileKind.KID)
                        .localManagerAccountId(resident.getId())
                        .build())))
        .isInstanceOf(InvitationRejections.RestrictedFirstAccount.class);
    assertThat(invitations.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should gate issuance as a whole surface")
  void shouldGateIssuanceAsWholeSurface() {
    var identity = authorization.currentIdentity();
    var invite = command();
    authorization.denyAll();

    assertThatThrownBy(() -> service.issueAccountInvitation(identity, invite))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should cancel only a pending invitation")
  void shouldCancelOnlyPendingInvitation() {
    var invitation =
        issued(service.issueAccountInvitation(authorization.currentIdentity(), command()))
            .invitation();

    var canceled =
        service.cancelAccountInvitation(authorization.currentIdentity(), invitation.getId());
    assertThat(canceled).isInstanceOf(Outcome.Accepted.class);

    var again =
        service.cancelAccountInvitation(authorization.currentIdentity(), invitation.getId());
    assertThat(rejectionOf(again)).isInstanceOf(InvitationRejections.InvitationNotPending.class);
  }

  @Test
  @DisplayName("Should audit the reset issue and replace the older pending code")
  void shouldAuditResetIssueAndReplaceOlderPendingCode() {
    var first =
        service.issuePasswordReset(authorization.currentIdentity(), resident.getId(), "locked out");
    var second =
        service.issuePasswordReset(
            authorization.currentIdentity(), resident.getId(), "locked out again");

    assertThat(first).isInstanceOf(Outcome.Accepted.class);
    assertThat(second).isInstanceOf(Outcome.Accepted.class);
    assertThat(resetCodes.findAll())
        .extracting(code -> code.getStatus())
        .containsExactlyInAnyOrder(
            PasswordResetCodeStatus.INVALIDATED, PasswordResetCodeStatus.PENDING);
    assertThat(audit.entries()).hasSize(2);
    assertThat(audit.entries().getFirst().operation()).isEqualTo("issuePasswordReset");
  }

  @Test
  @DisplayName("Should classify the reset issue's refusals under the oracle rule")
  void shouldClassifyResetIssueRefusalsUnderOracleRule() {
    assertThat(
            rejectionOf(
                service.issuePasswordReset(authorization.currentIdentity(), resident.getId(), " ")))
        .isInstanceOf(InvitationRejections.ReasonRequired.class);

    authorization.decideWith(
        intent ->
            intent instanceof Intent.IssuePasswordReset
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());
    assertThat(
            rejectionOf(
                service.issuePasswordReset(
                    authorization.currentIdentity(), resident.getId(), "locked out")))
        .isInstanceOf(InvitationRejections.ReauthenticationRequired.class);

    authorization.denyAll();
    assertThat(
            rejectionOf(
                service.issuePasswordReset(
                    authorization.currentIdentity(), resident.getId(), "locked out")))
        .isInstanceOf(InvitationRejections.AccountNotFound.class);
  }

  private IssueInvitationCommand command() {
    return IssueInvitationCommand.builder()
        .recipientEmail("kai@example.com")
        .householdId(household.getId())
        .householdRole(HouseholdRole.MEMBER)
        .profileName("Kai")
        .profileKind(ProfileKind.ADULT)
        .build();
  }

  private static CredentialIssuanceService.IssuedInvitation issued(
      Outcome<CredentialIssuanceService.IssuedInvitation, ?> outcome) {
    return outcome.fold(
        value -> value,
        rejections -> {
          throw new AssertionError("expected acceptance but got " + rejections);
        });
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
}
