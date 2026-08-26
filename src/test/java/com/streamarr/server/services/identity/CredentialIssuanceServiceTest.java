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
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakePasswordResetCodeRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.OpaqueOneTimeCodes;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
  private static final Duration INVITATION_TTL = Duration.ofDays(7);
  private static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);

  private final FakeAccountInvitationRepository invitations = new FakeAccountInvitationRepository();
  private final FakePasswordResetCodeRepository resetCodes = new FakePasswordResetCodeRepository();
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(profiles);
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
          profiles,
          audit,
          new OpaqueOneTimeCodes(),
          codeProperties(),
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private Household household;
  private UserAccount resident;

  @BeforeEach
  void setUp() {
    accounts.save(
        AccountFixture.defaultAccountBuilder()
            .id(authorization.currentIdentity().accountId())
            .serverAdmin(true)
            .build());
    household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    resident =
        accounts.save(
            AccountFixture.defaultAccountBuilder().householdId(household.getId()).build());
  }

  @Test
  @DisplayName("Should replace the older pending invitation when a new code is issued")
  void shouldReplaceOlderPendingInvitationWhenNewCodeIsIssued() {
    var first = issued(service.issueAccountInvitation(authorization.currentIdentity(), command()));
    var second = issued(service.issueAccountInvitation(authorization.currentIdentity(), command()));

    assertThat(first.code()).contains(".").isNotEqualTo(second.code());
    assertThat(first.invitation().getSecretDigest()).isNotEmpty();
    assertThat(first.invitation().getExpiresAt()).isEqualTo(NOW.plus(INVITATION_TTL));
    var statuses = invitations.findAll().stream().map(AccountInvitation::getStatus).toList();
    assertThat(statuses)
        .containsExactlyInAnyOrder(
            AccountInvitationStatus.INVALIDATED, AccountInvitationStatus.PENDING);
  }

  @Test
  @DisplayName("Should expire an older stale invitation when a new invitation is issued")
  void shouldExpireOlderStaleInvitationWhenNewInvitationIsIssued() {
    var stale =
        issued(service.issueAccountInvitation(authorization.currentIdentity(), command()))
            .invitation();
    stale.setExpiresAt(NOW.minusSeconds(1));

    issued(service.issueAccountInvitation(authorization.currentIdentity(), command()));

    assertThat(stale.getStatus()).isEqualTo(AccountInvitationStatus.EXPIRED);
  }

  @Test
  @DisplayName("Should leave storage unchanged when an invitation command is invalid")
  void shouldLeaveStorageUnchangedWhenInvitationCommandIsInvalid() {
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
                    command().toBuilder().profileName(" ").build())))
        .isInstanceOf(InvitationRejections.ProfileNameRequired.class);
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
  @DisplayName("Should reject a restricted Profile manager when they belong to another Household")
  void shouldRejectRestrictedProfileManagerWhenTheyBelongToAnotherHousehold() {
    var outsideManager =
        accounts.save(
            AccountFixture.defaultAccountBuilder().householdId(UUID.randomUUID()).build());

    var outcome =
        service.issueAccountInvitation(
            authorization.currentIdentity(),
            command().toBuilder()
                .profileKind(ProfileKind.KID)
                .localManagerAccountId(outsideManager.getId())
                .build());

    assertThat(rejectionOf(outcome)).isInstanceOf(InvitationRejections.LocalManagerNotFound.class);
  }

  @Test
  @DisplayName("Should reject a restricted Profile owner as a local Profile manager")
  void shouldRejectRestrictedProfileOwnerAsLocalProfileManager() {
    var restrictedProfile =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    var restrictedManager =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(household.getId())
                .householdRole(HouseholdRole.ADMIN)
                .personalProfileId(restrictedProfile.getId())
                .build());

    var outcome =
        service.issueAccountInvitation(
            authorization.currentIdentity(),
            command().toBuilder()
                .profileKind(ProfileKind.KID)
                .localManagerAccountId(restrictedManager.getId())
                .build());

    assertThat(rejectionOf(outcome)).isInstanceOf(InvitationRejections.LocalManagerNotFound.class);
    assertThat(invitations.findAll()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should issue a restricted invitation when the local manager is an eligible HouseholdAdmin")
  void shouldIssueRestrictedInvitationWhenLocalManagerIsEligibleHouseholdAdmin() {
    var manager = eligibleManager();

    var issued =
        issued(
            service.issueAccountInvitation(
                authorization.currentIdentity(),
                command().toBuilder()
                    .profileKind(ProfileKind.KID)
                    .localManagerAccountId(manager.getId())
                    .build()));

    assertThat(issued.invitation().getLocalManagerAccountId()).isEqualTo(manager.getId());
    assertThat(issued.invitation().getProfileKind()).isEqualTo(ProfileKind.KID);
    assertThat(issued.invitation().getStatus()).isEqualTo(AccountInvitationStatus.PENDING);
  }

  @Test
  @DisplayName(
      "Should issue a ceiling-bearing invitation when the local manager is an eligible HouseholdAdmin")
  void shouldIssueCeilingBearingInvitationWhenLocalManagerIsEligibleHouseholdAdmin() {
    var manager = eligibleManager();

    var issued =
        issued(
            service.issueAccountInvitation(
                authorization.currentIdentity(),
                command().toBuilder()
                    .maximumAllowedRatingAge(12)
                    .localManagerAccountId(manager.getId())
                    .build()));

    assertThat(issued.invitation().getMaximumAllowedRatingAge()).isEqualTo(12);
    assertThat(issued.invitation().getLocalManagerAccountId()).isEqualTo(manager.getId());
  }

  @Test
  @DisplayName("Should require a local manager when an adult invitation carries a content ceiling")
  void shouldRequireLocalManagerWhenAdultInvitationCarriesContentCeiling() {
    var outcome =
        service.issueAccountInvitation(
            authorization.currentIdentity(),
            command().toBuilder().maximumAllowedRatingAge(12).build());

    assertThat(rejectionOf(outcome)).isInstanceOf(InvitationRejections.LocalManagerRequired.class);
    assertThat(invitations.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a restricted Profile as HouseholdAdmin when an invitation is issued")
  void shouldRejectRestrictedProfileAsHouseholdAdminWhenInvitationIsIssued() {
    var manager = eligibleManager();

    var outcome =
        service.issueAccountInvitation(
            authorization.currentIdentity(),
            command().toBuilder()
                .profileKind(ProfileKind.KID)
                .householdRole(HouseholdRole.ADMIN)
                .localManagerAccountId(manager.getId())
                .build());

    assertThat(rejectionOf(outcome))
        .isInstanceOf(InvitationRejections.RestrictedHouseholdAdmin.class);
    assertThat(invitations.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a negative maximum allowed rating age when an invitation is issued")
  void shouldRejectNegativeMaximumAllowedRatingAgeWhenInvitationIsIssued() {
    var outcome =
        service.issueAccountInvitation(
            authorization.currentIdentity(),
            command().toBuilder().maximumAllowedRatingAge(-1).build());

    assertThat(rejectionOf(outcome))
        .isInstanceOf(InvitationRejections.MaximumAllowedRatingAgeInvalid.class);
    assertThat(invitations.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a duplicate Household Profile name when an invitation is issued")
  void shouldRejectDuplicateHouseholdProfileNameWhenInvitationIsIssued() {
    var existing =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(household.getId())
                .name("Kai")
                .build());
    profiles.shares().share(existing.getId(), household.getId(), true);

    var outcome = service.issueAccountInvitation(authorization.currentIdentity(), command());

    assertThat(rejectionOf(outcome)).isInstanceOf(InvitationRejections.ProfileNameTaken.class);
    assertThat(invitations.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should forbid invitation issuance when the caller is denied")
  void shouldForbidInvitationIssuanceWhenCallerIsDenied() {
    var identity = authorization.currentIdentity();
    var invite = command();
    authorization.denyAll();

    assertThatThrownBy(() -> service.issueAccountInvitation(identity, invite))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should forbid invitation cancellation when the caller is denied")
  void shouldForbidInvitationCancellationWhenCallerIsDenied() {
    var identity = authorization.currentIdentity();
    authorization.denyAll();

    assertThatThrownBy(() -> service.cancelAccountInvitation(identity, UUID.randomUUID()))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(invitations.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should cancel only a pending invitation when cancellation is requested")
  void shouldCancelOnlyPendingInvitationWhenCancellationIsRequested() {
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
  @DisplayName("Should audit and replace the older pending code when a reset is issued")
  void shouldAuditAndReplaceOlderPendingCodeWhenResetIsIssued() {
    var first =
        issuedReset(
            service.issuePasswordReset(
                authorization.currentIdentity(), resident.getId(), "locked out"));
    var second =
        issuedReset(
            service.issuePasswordReset(
                authorization.currentIdentity(), resident.getId(), "locked out again"));

    assertThat(first.resetCode().getExpiresAt()).isEqualTo(NOW.plus(PASSWORD_RESET_TTL));
    assertThat(second.resetCode().getExpiresAt()).isEqualTo(NOW.plus(PASSWORD_RESET_TTL));
    assertThat(resetCodes.findAll())
        .extracting(code -> code.getStatus())
        .containsExactlyInAnyOrder(
            PasswordResetCodeStatus.INVALIDATED, PasswordResetCodeStatus.PENDING);
    assertThat(audit.entries()).hasSize(2);
    assertThat(audit.entries().getFirst().operation()).isEqualTo("issuePasswordReset");
  }

  @Test
  @DisplayName("Should expire an older stale reset code when a new reset code is issued")
  void shouldExpireOlderStaleResetCodeWhenNewResetCodeIsIssued() {
    var stale =
        issuedReset(
                service.issuePasswordReset(
                    authorization.currentIdentity(), resident.getId(), "locked out"))
            .resetCode();
    stale.setExpiresAt(NOW.minusSeconds(1));

    issuedReset(
        service.issuePasswordReset(
            authorization.currentIdentity(), resident.getId(), "locked out again"));

    assertThat(stale.getStatus()).isEqualTo(PasswordResetCodeStatus.EXPIRED);
  }

  @Test
  @DisplayName("Should require a reason when a password reset is issued")
  void shouldRequireReasonWhenPasswordResetIsIssued() {
    assertThat(
            rejectionOf(
                service.issuePasswordReset(authorization.currentIdentity(), resident.getId(), " ")))
        .isInstanceOf(InvitationRejections.ReasonRequired.class);
    assertThat(resetCodes.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should require fresh reauthentication when a password reset is issued")
  void shouldRequireFreshReauthenticationWhenPasswordResetIsIssued() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.IssuePasswordReset
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());
    assertThat(
            rejectionOf(
                service.issuePasswordReset(
                    authorization.currentIdentity(), resident.getId(), "locked out")))
        .isInstanceOf(InvitationRejections.ReauthenticationRequired.class);
    assertThat(resetCodes.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should hide the reset target when issuance and Account visibility are denied")
  void shouldHideResetTargetWhenIssuanceAndAccountVisibilityAreDenied() {
    authorization.denyAll();

    assertThat(
            rejectionOf(
                service.issuePasswordReset(
                    authorization.currentIdentity(), resident.getId(), "locked out")))
        .isInstanceOf(InvitationRejections.AccountNotFound.class);
    assertThat(resetCodes.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should forbid a password reset when issuance is denied but the Account is visible")
  void shouldForbidPasswordResetWhenIssuanceIsDeniedButAccountIsVisible() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.IssuePasswordReset
                ? new Decision.Denied<>(Decision.DenialReason.POLICY)
                : allowed());

    assertThatThrownBy(
            () ->
                service.issuePasswordReset(
                    authorization.currentIdentity(), resident.getId(), "locked out"))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(resetCodes.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should hide an unknown Account when a password reset is issued")
  void shouldHideUnknownAccountWhenPasswordResetIsIssued() {
    var outcome =
        service.issuePasswordReset(
            authorization.currentIdentity(), UUID.randomUUID(), "locked out");

    assertThat(rejectionOf(outcome)).isInstanceOf(InvitationRejections.AccountNotFound.class);
    assertThat(resetCodes.findAll()).isEmpty();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should hide the reset target when it disappears before participants are locked")
  void shouldHideResetTargetWhenItDisappearsBeforeParticipantsAreLocked() {
    var vanishingAccounts = accountsMissingLockFor(resident.getId());
    var serviceWithVanishingTarget = serviceUsing(vanishingAccounts);

    var outcome =
        serviceWithVanishingTarget.issuePasswordReset(
            authorization.currentIdentity(), resident.getId(), "locked out");

    assertThat(rejectionOf(outcome)).isInstanceOf(InvitationRejections.AccountNotFound.class);
    assertThat(resetCodes.findAll()).isEmpty();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should forbid a password reset when the issuer disappears before locking")
  void shouldForbidPasswordResetWhenIssuerDisappearsBeforeLocking() {
    var issuerId = authorization.currentIdentity().accountId();
    var vanishingAccounts = accountsMissingLockFor(issuerId);
    var serviceWithVanishingIssuer = serviceUsing(vanishingAccounts);

    assertThatThrownBy(
            () ->
                serviceWithVanishingIssuer.issuePasswordReset(
                    authorization.currentIdentity(), resident.getId(), "locked out"))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(resetCodes.findAll()).isEmpty();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should fail closed when Account visibility cannot be decided for reset issuance")
  void shouldFailClosedWhenAccountVisibilityCannotBeDecidedForResetIssuance() {
    authorization.decideUnitWith(
        intent -> {
          if (intent instanceof Intent.IssuePasswordReset) {
            return new Decision.Denied<>(Decision.DenialReason.POLICY);
          }

          if (intent instanceof Intent.ViewAccountAdministration) {
            return new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE);
          }

          return allowed();
        });

    assertThatThrownBy(
            () ->
                service.issuePasswordReset(
                    authorization.currentIdentity(), resident.getId(), "locked out"))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  private static CredentialCodeProperties codeProperties() {
    return CredentialCodeProperties.builder()
        .invitationTtl(INVITATION_TTL)
        .passwordResetTtl(PASSWORD_RESET_TTL)
        .replacementLockTimeout(Duration.ofSeconds(5))
        .build();
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

  /** A HouseholdAdmin of the Household whose own Personal Profile is unrestricted. */
  private UserAccount eligibleManager() {
    var personalProfile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(household.getId())
                .name("Manager")
                .build());
    return accounts.save(
        AccountFixture.defaultAccountBuilder()
            .householdId(household.getId())
            .householdRole(HouseholdRole.ADMIN)
            .personalProfileId(personalProfile.getId())
            .build());
  }

  private LockOmittingUserAccountRepository accountsMissingLockFor(UUID missingAccountId) {
    var repository = new LockOmittingUserAccountRepository(missingAccountId);
    repository.save(accounts.findById(authorization.currentIdentity().accountId()).orElseThrow());
    repository.save(resident);
    return repository;
  }

  private CredentialIssuanceService serviceUsing(FakeUserAccountRepository accountRepository) {
    return new CredentialIssuanceService(
        authorization,
        invitations,
        resetCodes,
        accountRepository,
        households,
        profiles,
        audit,
        new OpaqueOneTimeCodes(),
        codeProperties(),
        new MutationTransactions(new FakeTransactionManager(), new ConstraintViolationTranslator()),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static CredentialIssuanceService.IssuedInvitation issued(
      Outcome<CredentialIssuanceService.IssuedInvitation, ?> outcome) {
    return outcome.fold(
        value -> value,
        rejections -> {
          throw new AssertionError("expected acceptance but got " + rejections);
        });
  }

  private static CredentialIssuanceService.IssuedResetCode issuedReset(
      Outcome<CredentialIssuanceService.IssuedResetCode, ?> outcome) {
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

  private static Decision<AuthorizationUnit> allowed() {
    return new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  }

  private static final class LockOmittingUserAccountRepository extends FakeUserAccountRepository {

    private final UUID missingAccountId;

    private LockOmittingUserAccountRepository(UUID missingAccountId) {
      this.missingAccountId = missingAccountId;
    }

    @Override
    public Set<UUID> lockByIds(Set<UUID> accountIds, Duration timeout) {
      return super.lockByIds(accountIds, timeout).stream()
          .filter(accountId -> !accountId.equals(missingAccountId))
          .collect(Collectors.toUnmodifiableSet());
    }
  }
}
