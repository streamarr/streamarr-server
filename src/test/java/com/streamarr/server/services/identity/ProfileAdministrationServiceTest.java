package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ProfilePinHasher;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfilePolicyTransition;
import com.streamarr.server.services.identity.ProfileAdministrationService.CreateProfileCommand;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The refusal shapes and write rules of Profile administration over fakes: the oracle rule per
 * mutation, the normalized transition written verbatim, PIN hashing before any transaction, the
 * would-lock preflight, and audits only for the break-glass and deletion winners.
 */
@Tag("UnitTest")
@DisplayName("Profile Administration Service Tests")
class ProfileAdministrationServiceTest {

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeAccountInvitationRepository invitations = new FakeAccountInvitationRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());
  private final RecordingEncoder encoder = new RecordingEncoder();
  private final FakeTransactionManager transactionManager = new FakeTransactionManager();

  private final ProfileAdministrationService service =
      new ProfileAdministrationService(
          authorization,
          profiles,
          managers,
          shares,
          households,
          accounts,
          invitations,
          audit,
          new ProfilePinHasher(encoder),
          new MutationTransactions(transactionManager, new ConstraintViolationTranslator()),
          Clock.systemUTC());

  private Household household;

  @BeforeEach
  void setUp() {
    household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
  }

  @Test
  @DisplayName(
      "Should create a Profile with its relationships when an eligible creator requests it")
  void shouldCreateProfileWithRelationshipsWhenEligibleCreatorRequestsIt() {
    var outcome =
        service.createProfile(
            identity(),
            CreateProfileCommand.builder()
                .householdId(household.getId())
                .name("  Kai  ")
                .kind(ProfileKind.KID)
                .build());

    var created = outcome.fold(profile -> profile, rejections -> null);
    assertThat(created).isNotNull();
    assertThat(created.getName()).isEqualTo("Kai");
    assertThat(created.getKind()).isEqualTo(ProfileKind.KID);
    assertThat(shares.isActivelyShared(created.getId(), household.getId())).isTrue();
    assertThat(managers.existsByAccountIdAndProfileId(identity().accountId(), created.getId()))
        .isTrue();
  }

  @Test
  @DisplayName("Should grant the named local manager when a remote ServerAdmin creates")
  void shouldGrantNamedLocalManagerWhenRemoteServerAdminCreates() {
    var localManagerProfile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var localManager =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(household.getId())
                .householdRole(HouseholdRole.ADMIN)
                .personalProfileId(localManagerProfile.getId())
                .build());

    var outcome =
        service.createProfile(
            identity(),
            CreateProfileCommand.builder()
                .householdId(household.getId())
                .name("Kai")
                .kind(ProfileKind.KID)
                .localManagerAccountId(localManager.getId())
                .build());

    var created = outcome.fold(profile -> profile, rejections -> null);
    assertThat(managers.existsByAccountIdAndProfileId(localManager.getId(), created.getId()))
        .isTrue();
  }

  @Test
  @DisplayName("Should default the Profile kind to Adult when creation omits it")
  void shouldDefaultProfileKindToAdultWhenCreationOmitsIt() {
    var outcome =
        service.createProfile(
            identity(),
            CreateProfileCommand.builder()
                .householdId(household.getId())
                .name("Kai")
                .maximumAllowedRatingAge(12)
                .build());

    var created = outcome.fold(profile -> profile, _ -> null);
    assertThat(created).isNotNull();
    assertThat(created.getKind()).isEqualTo(ProfileKind.ADULT);
  }

  @Test
  @DisplayName("Should add the creator only once when it is also the named local manager")
  void shouldAddCreatorOnlyOnceWhenItIsAlsoNamedLocalManager() {
    var identity = identity();
    var personal =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    accounts.save(
        AccountFixture.defaultAccountBuilder()
            .id(identity.accountId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.ADMIN)
            .personalProfileId(personal.getId())
            .build());

    var outcome =
        service.createProfile(
            identity,
            CreateProfileCommand.builder()
                .householdId(household.getId())
                .name("Kai")
                .kind(ProfileKind.KID)
                .localManagerAccountId(identity.accountId())
                .build());

    var created = outcome.fold(profile -> profile, _ -> null);
    assertThat(created).isNotNull();
    assertThat(managers.findByProfileId(created.getId())).hasSize(1);
  }

  @Test
  @DisplayName("Should reject a named local manager when it is not a HouseholdAdmin")
  void shouldRejectNamedLocalManagerWhenItIsNotHouseholdAdmin() {
    var personal =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var member =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(household.getId())
                .householdRole(HouseholdRole.MEMBER)
                .personalProfileId(personal.getId())
                .build());

    var outcome =
        service.createProfile(
            identity(),
            CreateProfileCommand.builder()
                .householdId(household.getId())
                .name("Kai")
                .localManagerAccountId(member.getId())
                .build());

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ManagerNotEligible.class);
  }

  @Test
  @DisplayName("Should reject a named local manager from another Household")
  void shouldRejectNamedLocalManagerWhenItBelongsToAnotherHousehold() {
    var otherHousehold = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var personal =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(otherHousehold.getId()).build());
    var manager =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(otherHousehold.getId())
                .householdRole(HouseholdRole.ADMIN)
                .personalProfileId(personal.getId())
                .build());

    var outcome =
        service.createProfile(
            identity(),
            CreateProfileCommand.builder()
                .householdId(household.getId())
                .name("Kai")
                .localManagerAccountId(manager.getId())
                .build());

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ManagerNotEligible.class);
  }

  @Test
  @DisplayName("Should reject a named local manager whose Personal Profile is restricted")
  void shouldRejectNamedLocalManagerWhenPersonalProfileIsRestricted() {
    var personal =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(household.getId())
                .maximumAllowedRatingAge(12)
                .build());
    var manager =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(household.getId())
                .householdRole(HouseholdRole.ADMIN)
                .personalProfileId(personal.getId())
                .build());

    var outcome =
        service.createProfile(
            identity(),
            CreateProfileCommand.builder()
                .householdId(household.getId())
                .name("Kai")
                .localManagerAccountId(manager.getId())
                .build());

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ManagerNotEligible.class);
  }

  @Test
  @DisplayName("Should reject a named local manager whose Personal Profile is a Kid")
  void shouldRejectNamedLocalManagerWhenPersonalProfileIsKid() {
    var personal =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    var manager =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(household.getId())
                .householdRole(HouseholdRole.ADMIN)
                .personalProfileId(personal.getId())
                .build());

    var outcome =
        service.createProfile(
            identity(),
            CreateProfileCommand.builder()
                .householdId(household.getId())
                .name("Kai")
                .localManagerAccountId(manager.getId())
                .build());

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ManagerNotEligible.class);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = " ")
  @DisplayName("Should refuse creation when the Profile name is blank")
  void shouldRefuseCreationWhenProfileNameIsBlank(String name) {
    assertThat(rejectionOf(service.createProfile(identity(), create(household.getId(), name))))
        .isInstanceOf(ProfileRejections.ProfileNameRequired.class);
  }

  @Test
  @DisplayName("Should refuse creation when the Household is unknown")
  void shouldRefuseCreationWhenHouseholdIsUnknown() {
    assertThat(rejectionOf(service.createProfile(identity(), create(UUID.randomUUID(), "Kai"))))
        .isInstanceOf(ProfileRejections.HouseholdNotFound.class);
  }

  @Test
  @DisplayName("Should refuse creation when the named local manager is unknown")
  void shouldRefuseCreationWhenNamedLocalManagerIsUnknown() {
    assertThat(
            rejectionOf(
                service.createProfile(
                    identity(),
                    CreateProfileCommand.builder()
                        .householdId(household.getId())
                        .name("Kai")
                        .localManagerAccountId(UUID.randomUUID())
                        .build())))
        .isInstanceOf(ProfileRejections.ProfileManagerNotEligible.class);
  }

  @Test
  @DisplayName("Should reject a negative Content Ceiling when creating a Profile")
  void shouldRejectNegativeContentCeilingWhenCreatingProfile() {
    var outcome =
        service.createProfile(
            identity(),
            CreateProfileCommand.builder()
                .householdId(household.getId())
                .name("Kai")
                .maximumAllowedRatingAge(-1)
                .build());

    assertThat(rejectionOf(outcome))
        .isInstanceOf(ProfileRejections.MaximumAllowedRatingAgeInvalid.class);
    assertThat(profiles.count()).isZero();
    assertThat(transactionManager.commits()).isZero();
  }

  @Test
  @DisplayName("Should return not found or forbidden when Household visibility changes")
  void shouldReturnNotFoundOrForbiddenWhenHouseholdVisibilityChanges() {
    var identity = identity();
    var command = create(household.getId(), "Kai");

    authorization.denyAll();
    assertThat(rejectionOf(service.createProfile(identity, command)))
        .isInstanceOf(ProfileRejections.HouseholdNotFound.class);

    authorization.decideUnitWith(
        intent -> intent instanceof Intent.CreateProfile ? denied() : allowed());
    assertThatThrownBy(() -> service.createProfile(identity, command))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName(
      "Should translate every known creation constraint when the database rejects creation")
  void shouldTranslateEveryKnownCreationConstraintWhenDatabaseRejectsCreation() {
    assertThat(creationRejectionFor("chk_household_profile_names_unique"))
        .isInstanceOf(ProfileRejections.ProfileNameTaken.class);
    assertThat(creationRejectionFor("chk_profile_home_anchor"))
        .isInstanceOf(ProfileRejections.EligibleManagerRequired.class);
    assertThat(creationRejectionFor("chk_restricted_account_holds_no_authority"))
        .isInstanceOf(ProfileRejections.ManagerNotEligible.class);
  }

  @Test
  @DisplayName("Should propagate an unknown creation constraint when the database rejects creation")
  void shouldPropagateUnknownCreationConstraintWhenDatabaseRejectsCreation() {
    var failingProfiles =
        new ConstraintFailingProfileRepository(
            shares, "chk_unexpected", ConstraintOperation.CREATE);
    var failingService = serviceWith(failingProfiles);
    var identity = identity();
    var command = create(household.getId(), "Kai");

    assertThatThrownBy(() -> failingService.createProfile(identity, command))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should write the normalized transition when authorization returns a decision value")
  void shouldWriteNormalizedTransitionWhenAuthorizationReturnsDecisionValue() {
    var profile =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    authorization.decidePolicyWith(
        _ ->
            new Decision.Allowed<>(
                new ProfilePolicyTransition(
                    ProfileKind.ADULT, 16, ProfilePolicyTransition.Classification.KIND_CHANGE)));

    var outcome = service.changeProfileKind(identity(), profile.getId(), ProfileKind.ADULT);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var written = profiles.findById(profile.getId()).orElseThrow();
    assertThat(written.getKind()).isEqualTo(ProfileKind.ADULT);
    assertThat(written.getMaximumAllowedRatingAge()).isEqualTo(16);
  }

  @Test
  @DisplayName("Should set the Content Ceiling when authorization normalizes the target")
  void shouldSetContentCeilingWhenAuthorizationNormalizesTarget() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    authorization.decidePolicyWith(
        _ ->
            new Decision.Allowed<>(
                new ProfilePolicyTransition(
                    ProfileKind.ADULT, 12, ProfilePolicyTransition.Classification.ORDINARY_EDIT)));

    var outcome = service.setProfileContentCeiling(identity(), profile.getId(), 12);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(profile.getId()).orElseThrow().getMaximumAllowedRatingAge())
        .isEqualTo(12);
  }

  @Test
  @DisplayName("Should report the missing ceremony when a policy change needs reauthentication")
  void shouldReportMissingCeremonyWhenPolicyChangeNeedsReauthentication() {
    var profile =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    authorization.decidePolicyWith(
        _ -> new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED));

    var outcome = service.changeProfileKind(identity(), profile.getId(), ProfileKind.ADULT);

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ReauthenticationRequired.class);
    assertThat(profiles.findById(profile.getId()).orElseThrow().getKind())
        .isEqualTo(ProfileKind.KID);
  }

  @Test
  @DisplayName("Should fail closed when a policy-change decision is unavailable")
  void shouldFailClosedWhenPolicyChangeDecisionIsUnavailable() {
    var profile =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = identity();
    var profileId = profile.getId();

    assertThatThrownBy(() -> service.changeProfileKind(identity, profileId, ProfileKind.ADULT))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should hide or forbid a policy denial when Profile visibility changes")
  void shouldHideOrForbidPolicyDenialWhenProfileVisibilityChanges() {
    var profile =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    var identity = identity();
    var profileId = profile.getId();
    authorization.decidePolicyWith(_ -> new Decision.Denied<>(Decision.DenialReason.POLICY));

    assertThatThrownBy(() -> service.changeProfileKind(identity, profileId, ProfileKind.ADULT))
        .isInstanceOf(AccessDeniedException.class);

    authorization.denyAll();
    assertThat(rejectionOf(service.changeProfileKind(identity, profileId, ProfileKind.ADULT)))
        .isInstanceOf(ProfileRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should return not found when an allowed policy write loses the Profile")
  void shouldReturnNotFoundWhenAllowedPolicyWriteLosesProfile() {
    authorization.decidePolicyWith(
        _ ->
            new Decision.Allowed<>(
                new ProfilePolicyTransition(
                    ProfileKind.ADULT, null, ProfilePolicyTransition.Classification.KIND_CHANGE)));

    var outcome = service.changeProfileKind(identity(), UUID.randomUUID(), ProfileKind.ADULT);

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should translate every known policy constraint when the database rejects the write")
  void shouldTranslateEveryKnownPolicyConstraintWhenDatabaseRejectsWrite() {
    assertThat(policyRejectionFor("chk_profile_home_anchor"))
        .isInstanceOf(ProfileRejections.EligibleManagerRequired.class);
    assertThat(policyRejectionFor("chk_restricted_account_holds_no_authority"))
        .isInstanceOf(ProfileRejections.RestrictedAccountAuthority.class);
    assertThat(policyRejectionFor("chk_hosting_household_retains_eligible_admin"))
        .isInstanceOf(ProfileRejections.HostingHouseholdLacksEligibleAdmin.class);
  }

  @Test
  @DisplayName("Should propagate an unknown policy constraint when the database rejects the write")
  void shouldPropagateUnknownPolicyConstraintWhenDatabaseRejectsWrite() {
    var failingProfiles =
        new ConstraintFailingProfileRepository(
            shares, "chk_unexpected", ConstraintOperation.POLICY);
    var profile =
        failingProfiles.save(
            ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    var failingService = serviceWith(failingProfiles);
    allowPolicyTransition();
    var identity = identity();
    var profileId = profile.getId();

    assertThatThrownBy(
            () -> failingService.changeProfileKind(identity, profileId, ProfileKind.ADULT))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should set the picture when an ordinary edit is allowed")
  void shouldSetPictureWhenOrdinaryEditIsAllowed() {
    var profile =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());

    var outcome = service.setProfilePicture(identity(), profile.getId(), "kai.png");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(profile.getId()).orElseThrow().getPicture()).isEqualTo("kai.png");
  }

  @Test
  @DisplayName("Should clear the Content Ceiling when an ordinary edit is allowed")
  void shouldClearContentCeilingWhenOrdinaryEditIsAllowed() {
    var profile =
        profiles.save(
            ProfileFixture.kidProfileBuilder()
                .householdId(household.getId())
                .maximumAllowedRatingAge(12)
                .build());
    authorization.decidePolicyWith(
        _ ->
            new Decision.Allowed<>(
                new ProfilePolicyTransition(
                    ProfileKind.KID, null, ProfilePolicyTransition.Classification.ORDINARY_EDIT)));

    var outcome = service.clearProfileContentCeiling(identity(), profile.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(profile.getId()).orElseThrow().getMaximumAllowedRatingAge())
        .isNull();
  }

  @Test
  @DisplayName("Should reject a negative Content Ceiling before authorization when setting it")
  void shouldRejectNegativeContentCeilingBeforeAuthorizationWhenSettingIt() {
    var profile =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());

    var outcome = service.setProfileContentCeiling(identity(), profile.getId(), -1);

    assertThat(rejectionOf(outcome))
        .isInstanceOf(ProfileRejections.MaximumAllowedRatingAgeInvalid.class);
    assertThat(authorization.recordedIntents()).isEmpty();
    assertThat(transactionManager.commits()).isZero();
    assertThat(profiles.findById(profile.getId()).orElseThrow().getMaximumAllowedRatingAge())
        .isNull();
  }

  @Test
  @DisplayName("Should reject a blank name without writing when renaming a Profile")
  void shouldRejectBlankNameWithoutWritingWhenRenamingProfile() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var originalName = profile.getName();

    var outcome = service.renameProfile(identity(), profile.getId(), "   ");

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNameRequired.class);
    assertThat(profiles.findById(profile.getId()).orElseThrow().getName()).isEqualTo(originalName);
    assertThat(transactionManager.commits()).isZero();
  }

  @Test
  @DisplayName("Should trim and rename a Profile when the edit is allowed")
  void shouldTrimAndRenameProfileWhenEditIsAllowed() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var outcome = service.renameProfile(identity(), profile.getId(), "  Kai  ");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(profile.getId()).orElseThrow().getName()).isEqualTo("Kai");
  }

  @Test
  @DisplayName("Should translate the unique-name constraint when the database rejects a rename")
  void shouldTranslateUniqueNameConstraintWhenDatabaseRejectsRename() {
    var failingProfiles =
        new ConstraintFailingProfileRepository(
            shares, "chk_household_profile_names_unique", ConstraintOperation.RENAME);
    var profile =
        failingProfiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var outcome = serviceWith(failingProfiles).renameProfile(identity(), profile.getId(), "Kai");

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNameTaken.class);
  }

  @Test
  @DisplayName("Should return not found when renaming a missing Profile")
  void shouldReturnNotFoundWhenRenamingMissingProfile() {
    var outcome = service.renameProfile(identity(), UUID.randomUUID(), "Kai");

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNotFound.class);
    assertThat(transactionManager.rollbacks()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should return not found when a policy change targets a missing Profile")
  void shouldReturnNotFoundWhenPolicyChangeTargetsMissingProfile() {
    var outcome = service.changeProfileKind(identity(), UUID.randomUUID(), ProfileKind.KID);

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should return not found when setting a picture on a missing Profile")
  void shouldReturnNotFoundWhenSettingPictureOnMissingProfile() {
    var outcome = service.setProfilePicture(identity(), UUID.randomUUID(), "kai.png");

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNotFound.class);
    assertThat(transactionManager.rollbacks()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should hide or forbid ordinary edits when Profile visibility changes")
  void shouldHideOrForbidOrdinaryEditsWhenProfileVisibilityChanges() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var identity = identity();
    var profileId = profile.getId();
    authorization.decideUnitWith(
        intent -> intent instanceof Intent.RenameProfile ? denied() : allowed());

    assertThatThrownBy(() -> service.renameProfile(identity, profileId, "Kai"))
        .isInstanceOf(AccessDeniedException.class);

    authorization.denyAll();
    assertThat(rejectionOf(service.renameProfile(identity, profileId, "Kai")))
        .isInstanceOf(ProfileRejections.ProfileNotFound.class);
    assertThat(rejectionOf(service.setProfilePicture(identity, profileId, "kai.png")))
        .isInstanceOf(ProfileRejections.ProfileNotFound.class);
    assertThat(rejectionOf(service.setProfilePin(identity, profileId, "4242")))
        .isInstanceOf(ProfileRejections.ProfileNotFound.class);
    assertThat(rejectionOf(service.removeProfilePin(identity, profileId)))
        .isInstanceOf(ProfileRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should refuse a malformed PIN before hashing or writing when setting it")
  void shouldRefuseMalformedPinBeforeHashingOrWritingWhenSettingIt() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    assertThat(rejectionOf(service.setProfilePin(identity(), profile.getId(), "abc")))
        .isInstanceOf(ProfileRejections.PinMalformed.class);
    assertThat(encoder.encodedValues()).isEmpty();
    assertThat(transactionManager.commits()).isZero();
    assertThat(profiles.findById(profile.getId()).orElseThrow().getPinHash()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"123", "123456789", "12a4"})
  @DisplayName("Should reject a PIN when it falls outside the four-to-eight-digit boundary")
  void shouldRejectPinWhenOutsideFourToEightDigitBoundary(String pin) {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    assertThat(rejectionOf(service.setProfilePin(identity(), profile.getId(), pin)))
        .isInstanceOf(ProfileRejections.PinMalformed.class);
    assertThat(encoder.encodedValues()).isEmpty();
    assertThat(profiles.findById(profile.getId()).orElseThrow().getPinHash()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"1234", "12345678"})
  @DisplayName("Should accept and hash a PIN outside a transaction when it is on a valid boundary")
  void shouldAcceptAndHashPinOutsideTransactionWhenOnValidBoundary(String pin) {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var outcome = service.setProfilePin(identity(), profile.getId(), pin);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var stored = profiles.findById(profile.getId()).orElseThrow().getPinHash();
    assertThat(stored).isNotBlank().isNotEqualTo(pin).doesNotContain(pin);
    assertThat(encoder.encodedValues()).containsExactly(pin);
    assertThat(encoder.transactionStates()).containsExactly(false);
    assertThat(transactionManager.commits()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should return not found before hashing when setting a PIN on a missing Profile")
  void shouldReturnNotFoundBeforeHashingWhenSettingPinOnMissingProfile() {
    var outcome = service.setProfilePin(identity(), UUID.randomUUID(), "4242");

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNotFound.class);
    assertThat(encoder.encodedValues()).isEmpty();
    assertThat(transactionManager.rollbacks()).isZero();
  }

  @Test
  @DisplayName("Should return not found when removing the PIN from a missing Profile")
  void shouldReturnNotFoundWhenRemovingPinFromMissingProfile() {
    var outcome = service.removeProfilePin(identity(), UUID.randomUUID());

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNotFound.class);
    assertThat(transactionManager.rollbacks()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should refuse removing a PIN when the safety rule still requires it")
  void shouldRefuseRemovingPinWhenSafetyRuleStillRequiresIt() {
    var adult =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(household.getId())
                .pinHash("hash")
                .build());
    var kid =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    shares.share(adult.getId(), household.getId(), false);
    shares.share(kid.getId(), household.getId(), false);

    var outcome = service.removeProfilePin(identity(), adult.getId());

    var rejection = rejectionOf(outcome);
    assertThat(rejection).isInstanceOf(ProfileRejections.WouldLockProfile.class);
    var wouldLock = (ProfileRejections.WouldLockProfile) rejection;
    assertThat(wouldLock.householdId()).isEqualTo(household.getId());
    assertThat(wouldLock.householdName()).contains(household.getName());
    assertThat(profiles.findById(adult.getId()).orElseThrow().getPinHash()).isEqualTo("hash");
  }

  @Test
  @DisplayName("Should refuse removing a PIN when another shared Household requires it")
  void shouldRefuseRemovingPinWhenAnotherSharedHouseholdRequiresIt() {
    var otherHousehold = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var adult =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(household.getId())
                .pinHash("hash")
                .build());
    var otherHouseholdKid =
        profiles.save(
            ProfileFixture.kidProfileBuilder().householdId(otherHousehold.getId()).build());
    shares.share(adult.getId(), household.getId(), false);
    shares.share(adult.getId(), otherHousehold.getId(), false);
    shares.share(otherHouseholdKid.getId(), otherHousehold.getId(), false);

    var rejection = rejectionOf(service.removeProfilePin(identity(), adult.getId()));

    assertThat(rejection).isInstanceOf(ProfileRejections.WouldLockProfile.class);
    var wouldLock = (ProfileRejections.WouldLockProfile) rejection;
    assertThat(wouldLock.householdId()).isEqualTo(otherHousehold.getId());
    assertThat(wouldLock.householdName()).contains(otherHousehold.getName());
    assertThat(profiles.findById(adult.getId()).orElseThrow().getPinHash()).isEqualTo("hash");
  }

  @Test
  @DisplayName("Should withhold the Household name when the caller may not view it")
  void shouldWithholdHouseholdNameWhenCallerMayNotViewIt() {
    var adult =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(household.getId())
                .pinHash("hash")
                .build());
    var kid =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    shares.share(adult.getId(), household.getId(), false);
    shares.share(kid.getId(), household.getId(), false);
    authorization.decideUnitWith(
        intent -> intent instanceof Intent.ViewHouseholdAdministration ? denied() : allowed());

    var rejection = rejectionOf(service.removeProfilePin(identity(), adult.getId()));

    assertThat(((ProfileRejections.WouldLockProfile) rejection).householdName()).isEmpty();
  }

  @Test
  @DisplayName("Should remove the PIN when no Household's safety rule requires it")
  void shouldRemovePinWhenNoHouseholdSafetyRuleRequiresIt() {
    var adult =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(household.getId())
                .pinHash("hash")
                .build());
    shares.share(adult.getId(), household.getId(), false);

    var outcome = service.removeProfilePin(identity(), adult.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(adult.getId()).orElseThrow().getPinHash()).isNull();
  }

  @Test
  @DisplayName("Should audit the administrative PIN reset with its reason when it succeeds")
  void shouldAuditAdministrativePinResetWithReasonWhenItSucceeds() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var outcome =
        service.administrativelyResetProfilePin(
            identity(), profile.getId(), "4242", "locked out kid");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(audit.entries()).hasSize(1);
    assertThat(audit.entries().getFirst().operation()).isEqualTo("administrativelyResetProfilePin");
    assertThat(audit.entries().getFirst().reason()).isEqualTo("locked out kid");
  }

  @Test
  @DisplayName("Should refuse a malformed PIN before hashing or auditing an administrative reset")
  void shouldRefuseMalformedPinBeforeHashingOrAuditingAdministrativeReset() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var rejection =
        rejectionOf(
            service.administrativelyResetProfilePin(
                identity(), profile.getId(), "12a4", "locked out"));

    assertThat(rejection).isInstanceOf(ProfileRejections.PinMalformed.class);
    assertThat(encoder.encodedValues()).isEmpty();
    assertThat(transactionManager.commits()).isZero();
    assertThat(profiles.findById(profile.getId()).orElseThrow().getPinHash()).isNull();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should return not found without auditing when administratively resetting a missing Profile PIN")
  void shouldReturnNotFoundWithoutAuditingWhenAdministrativelyResettingMissingProfilePin() {
    var outcome =
        service.administrativelyResetProfilePin(
            identity(), UUID.randomUUID(), "4242", "locked out");

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNotFound.class);
    assertThat(audit.entries()).isEmpty();
    assertThat(transactionManager.rollbacks()).isZero();
  }

  @Test
  @DisplayName("Should hide a denied administrative PIN reset when the Profile may not be viewed")
  void shouldHideDeniedAdministrativePinResetWhenProfileMayNotBeViewed() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    authorization.denyAll();

    var outcome =
        service.administrativelyResetProfilePin(identity(), profile.getId(), "4242", "locked out");

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNotFound.class);
    assertThat(encoder.encodedValues()).isEmpty();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should report reauthentication when an administrative PIN reset requires it")
  void shouldReportReauthenticationWhenAdministrativePinResetRequiresIt() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.AdministrativelyResetProfilePin
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());

    var outcome =
        service.administrativelyResetProfilePin(identity(), profile.getId(), "4242", "locked out");

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ReauthenticationRequired.class);
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should require a reason before deciding an administrative PIN reset")
  void shouldRequireReasonBeforeDecidingAdministrativePinReset() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var rejection =
        rejectionOf(
            service.administrativelyResetProfilePin(identity(), profile.getId(), "4242", " "));

    assertThat(rejection).isInstanceOf(ProfileRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should delete the Profile and audit once when deletion succeeds")
  void shouldDeleteProfileAndAuditOnceWhenDeletionSucceeds() {
    var orphan =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var outcome = service.deleteProfile(identity(), orphan.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(orphan.getId())).isEmpty();
    assertThat(audit.entries())
        .extracting(entry -> entry.operation())
        .containsExactly("deleteProfile");
  }

  @Test
  @DisplayName("Should return not found without auditing when deleting a missing Profile")
  void shouldReturnNotFoundWithoutAuditingWhenDeletingMissingProfile() {
    var outcome = service.deleteProfile(identity(), UUID.randomUUID());

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ProfileNotFound.class);
    assertThat(audit.entries()).isEmpty();
    assertThat(transactionManager.rollbacks()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should report reauthentication when deletion requires a fresh ceremony")
  void shouldReportReauthenticationWhenDeletionRequiresFreshCeremony() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.DeleteProfile
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());

    var outcome = service.deleteProfile(identity(), profile.getId());

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ReauthenticationRequired.class);
    assertThat(profiles.findById(profile.getId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should invalidate only pending CONNECT invitations when the Profile is deleted")
  void shouldInvalidateOnlyPendingConnectInvitationsWhenProfileIsDeleted() {
    var orphan =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var invitation =
        invitations.save(
            AccountInvitation.builder()
                .recipientEmail("joe@example.com")
                .householdId(household.getId())
                .householdName("Home")
                .householdRole(HouseholdRole.MEMBER)
                .mode(AccountInvitationMode.CONNECT)
                .profileId(orphan.getId())
                .profileName("Joe")
                .profileKind(ProfileKind.ADULT)
                .issuerAccountId(UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId("pub")
                .secretDigest(new byte[] {1})
                .build());
    var decided =
        invitations.save(
            invitation.toBuilder()
                .id(null)
                .status(AccountInvitationStatus.ACCEPTED)
                .publicId("accepted")
                .build());

    service.deleteProfile(identity(), orphan.getId());

    assertThat(invitations.findById(invitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(invitations.findById(decided.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.ACCEPTED);
  }

  @Test
  @DisplayName("Should explain refusal only to a caller who may view the Profile")
  void shouldExplainRefusalOnlyToCallerWhoMayViewProfile() {
    var orphan =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    authorization.decideUnitWith(
        intent -> intent instanceof Intent.DeleteProfile ? denied() : allowed());
    assertThat(rejectionOf(service.deleteProfile(identity(), orphan.getId())))
        .isInstanceOf(ProfileRejections.ProfileNotDeletable.class);

    authorization.denyAll();
    assertThat(rejectionOf(service.deleteProfile(identity(), orphan.getId())))
        .isInstanceOf(ProfileRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should fail closed when an ordinary edit unexpectedly requires reauthentication")
  void shouldFailClosedWhenOrdinaryEditUnexpectedlyRequiresReauthentication() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.RenameProfile
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());
    var identity = identity();
    var profileId = profile.getId();

    assertThatThrownBy(() -> service.renameProfile(identity, profileId, "Kai"))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when an ordinary edit decision is unavailable")
  void shouldFailClosedWhenOrdinaryEditDecisionIsUnavailable() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = identity();
    var profileId = profile.getId();

    assertThatThrownBy(() -> service.renameProfile(identity, profileId, "Kai"))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  private Object creationRejectionFor(String constraint) {
    var failingProfiles =
        new ConstraintFailingProfileRepository(shares, constraint, ConstraintOperation.CREATE);
    return rejectionOf(
        serviceWith(failingProfiles).createProfile(identity(), create(household.getId(), "Kai")));
  }

  private Object policyRejectionFor(String constraint) {
    var failingProfiles =
        new ConstraintFailingProfileRepository(shares, constraint, ConstraintOperation.POLICY);
    var profile =
        failingProfiles.save(
            ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    allowPolicyTransition();
    return rejectionOf(
        serviceWith(failingProfiles)
            .changeProfileKind(identity(), profile.getId(), ProfileKind.ADULT));
  }

  private void allowPolicyTransition() {
    authorization.decidePolicyWith(
        _ ->
            new Decision.Allowed<>(
                new ProfilePolicyTransition(
                    ProfileKind.ADULT, null, ProfilePolicyTransition.Classification.KIND_CHANGE)));
  }

  private ProfileAdministrationService serviceWith(ProfileRepository profileRepository) {
    return new ProfileAdministrationService(
        authorization,
        profileRepository,
        managers,
        shares,
        households,
        accounts,
        invitations,
        audit,
        new ProfilePinHasher(encoder),
        new MutationTransactions(transactionManager, new ConstraintViolationTranslator()),
        Clock.systemUTC());
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  private static CreateProfileCommand create(UUID householdId, String name) {
    return CreateProfileCommand.builder().householdId(householdId).name(name).build();
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

  private static DataIntegrityViolationException violation(String constraint) {
    var message = "violated constraint \"" + constraint + "\"";
    return new DataIntegrityViolationException(
        message,
        new ConstraintViolationException(message, new SQLException(message, "23514"), constraint));
  }

  private enum ConstraintOperation {
    CREATE,
    RENAME,
    POLICY
  }

  private static final class ConstraintFailingProfileRepository extends FakeProfileRepository {

    private final String constraint;
    private final ConstraintOperation operation;

    private ConstraintFailingProfileRepository(
        FakeProfileHouseholdShareRepository shares,
        String constraint,
        ConstraintOperation operation) {
      super(shares);
      this.constraint = constraint;
      this.operation = operation;
    }

    @Override
    public <S extends Profile> S saveAndFlush(S entity) {
      if (operation == ConstraintOperation.CREATE) {
        throw violation(constraint);
      }

      return super.saveAndFlush(entity);
    }

    @Override
    public boolean tryRename(UUID profileId, String name) {
      if (operation == ConstraintOperation.RENAME) {
        throw violation(constraint);
      }

      return super.tryRename(profileId, name);
    }

    @Override
    public boolean tryApplyPolicy(UUID profileId, ProfilePolicyTarget target) {
      if (operation == ConstraintOperation.POLICY) {
        throw violation(constraint);
      }

      return super.tryApplyPolicy(profileId, target);
    }
  }

  private static final class RecordingEncoder implements PasswordEncoder {

    private final List<String> encodedValues = new ArrayList<>();
    private final List<Boolean> transactionStates = new ArrayList<>();

    List<String> encodedValues() {
      return List.copyOf(encodedValues);
    }

    List<Boolean> transactionStates() {
      return List.copyOf(transactionStates);
    }

    @Override
    public String encode(CharSequence rawPassword) {
      encodedValues.add(rawPassword.toString());
      transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
      return encodedPin(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return encodedPassword.equals(encodedPin(rawPassword));
    }

    private static String encodedPin(CharSequence rawPassword) {
      return "encoded-pin-" + rawPassword.length();
    }
  }
}
