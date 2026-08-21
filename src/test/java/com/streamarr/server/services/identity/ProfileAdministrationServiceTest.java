package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

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
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final ProfileAdministrationService service =
      new ProfileAdministrationService(
          authorization,
          profiles,
          managers,
          shares,
          households,
          accounts,
          audit,
          new ProfilePinHasher(new PlainEncoder()),
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()));

  private Household household;

  @BeforeEach
  void setUp() {
    household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
  }

  @Test
  @DisplayName("Should create a Profile with its home share and creator manager")
  void shouldCreateProfileWithHomeShareAndCreatorManager() {
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
    var localManager = accounts.save(AccountFixture.defaultAccountBuilder().build());

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
  @DisplayName("Should refuse creation for blank names and unknown Households and managers")
  void shouldRefuseCreationForBlankNamesAndUnknownHouseholdsAndManagers() {
    assertThat(rejectionOf(service.createProfile(identity(), create(household.getId(), " "))))
        .isInstanceOf(ProfileRejections.ProfileNameRequired.class);
    assertThat(rejectionOf(service.createProfile(identity(), create(UUID.randomUUID(), "Kai"))))
        .isInstanceOf(ProfileRejections.HouseholdNotFound.class);
    assertThat(
            rejectionOf(
                service.createProfile(
                    identity(),
                    CreateProfileCommand.builder()
                        .householdId(household.getId())
                        .name("Kai")
                        .localManagerAccountId(UUID.randomUUID())
                        .build())))
        .isInstanceOf(ProfileRejections.LocalManagerNotFound.class);
  }

  @Test
  @DisplayName("Should read a hidden Household as not found and a visible one as forbidden")
  void shouldReadHiddenHouseholdAsNotFoundAndVisibleOneAsForbidden() {
    var identity = identity();
    var command = create(household.getId(), "Kai");

    authorization.denyAll();
    assertThat(rejectionOf(service.createProfile(identity, command)))
        .isInstanceOf(ProfileRejections.HouseholdNotFound.class);

    authorization.decideWith(
        intent -> intent instanceof Intent.CreateProfile ? denied() : allowed());
    assertThatThrownBy(() -> service.createProfile(identity, command))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should write exactly the normalized transition the decision returned")
  void shouldWriteExactlyNormalizedTransitionDecisionReturned() {
    var profile =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    authorization.decideWith(
        intent ->
            intent instanceof Intent.ProfilePolicyChange
                ? new Decision.Allowed<>(
                    new ProfilePolicyTransition(
                        ProfileKind.ADULT, 16, ProfilePolicyTransition.Classification.KIND_CHANGE))
                : allowed());

    var outcome = service.changeProfileKind(identity(), profile.getId(), ProfileKind.ADULT);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var written = profiles.findById(profile.getId()).orElseThrow();
    assertThat(written.getKind()).isEqualTo(ProfileKind.ADULT);
    assertThat(written.getMaximumAllowedRatingAge()).isEqualTo(16);
  }

  @Test
  @DisplayName("Should report the missing ceremony when a policy change needs reauthentication")
  void shouldReportMissingCeremonyWhenPolicyChangeNeedsReauthentication() {
    var profile =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
    authorization.decideWith(
        intent ->
            intent instanceof Intent.ProfilePolicyChange
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());

    var outcome = service.changeProfileKind(identity(), profile.getId(), ProfileKind.ADULT);

    assertThat(rejectionOf(outcome)).isInstanceOf(ProfileRejections.ReauthenticationRequired.class);
    assertThat(profiles.findById(profile.getId()).orElseThrow().getKind())
        .isEqualTo(ProfileKind.KID);
  }

  @Test
  @DisplayName("Should set a picture and clear a ceiling through their ordinary edits")
  void shouldSetPictureAndClearCeilingThroughOrdinaryEdits() {
    var profile =
        profiles.save(
            ProfileFixture.kidProfileBuilder()
                .householdId(household.getId())
                .maximumAllowedRatingAge(12)
                .build());
    authorization.decideWith(
        intent ->
            intent instanceof Intent.ProfilePolicyChange
                ? new Decision.Allowed<>(
                    new ProfilePolicyTransition(
                        ProfileKind.KID,
                        null,
                        ProfilePolicyTransition.Classification.ORDINARY_EDIT))
                : allowed());

    assertThat(service.setProfilePicture(identity(), profile.getId(), "kai.png"))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(profile.getId()).orElseThrow().getPicture()).isEqualTo("kai.png");

    assertThat(service.clearProfileContentCeiling(identity(), profile.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(profile.getId()).orElseThrow().getMaximumAllowedRatingAge())
        .isNull();
  }

  @Test
  @DisplayName("Should refuse a malformed PIN before any hashing or writing")
  void shouldRefuseMalformedPinBeforeAnyHashingOrWriting() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    assertThat(rejectionOf(service.setProfilePin(identity(), profile.getId(), "abc")))
        .isInstanceOf(ProfileRejections.PinMalformed.class);
    assertThat(profiles.findById(profile.getId()).orElseThrow().getPinHash()).isNull();
  }

  @Test
  @DisplayName("Should store only the PIN hash, never the raw PIN")
  void shouldStoreOnlyPinHashNeverRawPin() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var outcome = service.setProfilePin(identity(), profile.getId(), "4242");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var stored = profiles.findById(profile.getId()).orElseThrow().getPinHash();
    assertThat(stored).isNotBlank().isNotEqualTo("4242").doesNotContain("4242");
  }

  @Test
  @DisplayName("Should refuse clearing a PIN that the safety rule still requires")
  void shouldRefuseClearingPinSafetyRuleStillRequires() {
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

    var outcome = service.clearProfilePin(identity(), adult.getId());

    var rejection = rejectionOf(outcome);
    assertThat(rejection).isInstanceOf(ProfileRejections.WouldLockProfile.class);
    var wouldLock = (ProfileRejections.WouldLockProfile) rejection;
    assertThat(wouldLock.householdId()).isEqualTo(household.getId());
    assertThat(wouldLock.householdName()).contains(household.getName());
    assertThat(profiles.findById(adult.getId()).orElseThrow().getPinHash()).isEqualTo("hash");
  }

  @Test
  @DisplayName("Should withhold the Household name from a caller who may not view it")
  void shouldWithholdHouseholdNameFromCallerWhoMayNotViewIt() {
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
    authorization.decideWith(
        intent -> intent instanceof Intent.ViewHouseholdAdministration ? denied() : allowed());

    var rejection = rejectionOf(service.clearProfilePin(identity(), adult.getId()));

    assertThat(((ProfileRejections.WouldLockProfile) rejection).householdName()).isEmpty();
  }

  @Test
  @DisplayName("Should clear the PIN when no Household's safety rule requires it")
  void shouldClearPinWhenNoHouseholdSafetyRuleRequiresIt() {
    var adult =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(household.getId())
                .pinHash("hash")
                .build());
    shares.share(adult.getId(), household.getId(), false);

    var outcome = service.clearProfilePin(identity(), adult.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(adult.getId()).orElseThrow().getPinHash()).isNull();
  }

  @Test
  @DisplayName("Should audit the PIN override winner with its reason")
  void shouldAuditPinOverrideWinnerWithItsReason() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var outcome = service.overrideProfilePin(identity(), profile.getId(), "4242", "locked out kid");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(audit.entries()).hasSize(1);
    assertThat(audit.entries().getFirst().operation()).isEqualTo("overrideProfilePin");
    assertThat(audit.entries().getFirst().reason()).isEqualTo("locked out kid");
  }

  @Test
  @DisplayName("Should require a reason before deciding a PIN override")
  void shouldRequireReasonBeforeDecidingPinOverride() {
    var profile =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    var rejection =
        rejectionOf(service.overrideProfilePin(identity(), profile.getId(), "4242", " "));

    assertThat(rejection).isInstanceOf(ProfileRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should delete a Profile and audit exactly one win")
  void shouldDeleteProfileAndAuditExactlyOneWin() {
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
  @DisplayName("Should explain refusal only to a caller who may view the Profile")
  void shouldExplainRefusalOnlyToCallerWhoMayViewProfile() {
    var orphan =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());

    authorization.decideWith(
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
    authorization.decideWith(
        intent ->
            intent instanceof Intent.RenameProfile
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());

    assertThatThrownBy(() -> service.renameProfile(identity(), profile.getId(), "Kai"))
        .isInstanceOf(AuthorizationUnavailableException.class);
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

  private static Decision<?> allowed() {
    return new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  }

  private static Decision<?> denied() {
    return new Decision.Denied<>(Decision.DenialReason.POLICY);
  }

  private static final class PlainEncoder implements PasswordEncoder {
    @Override
    public String encode(CharSequence rawPassword) {
      return "encoded-pin";
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return encodedPassword.equals(encode(rawPassword));
    }
  }
}
