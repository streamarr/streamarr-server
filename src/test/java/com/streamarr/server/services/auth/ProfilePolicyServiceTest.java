package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.exceptions.ProfileSafetyViolationException;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Policy Service Tests")
class ProfilePolicyServiceTest {

  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeProfileManagerRepository managerRepository = new FakeProfileManagerRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final HouseholdProfileSafetyService safetyService =
      new HouseholdProfileSafetyService(shareRepository, profileRepository);
  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeSecurityAuditEventRepository auditRepository =
      new FakeSecurityAuditEventRepository();
  private final ProfilePolicyService service =
      new ProfilePolicyService(
          profileRepository,
          managerRepository,
          safetyService,
          new KidProfileManagerPolicy(
              profileRepository, managerRepository, shareRepository, accountRepository),
          new SecurityAuditService(auditRepository));

  @Test
  @DisplayName("Should set profile kind without changing content ceiling or PIN")
  void shouldSetProfileKindWithoutChangingContentCeilingOrPin() {
    var managerId = UUID.randomUUID();
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Managed Profile")
                .kind(ProfileKind.ADULT)
                .maximumAllowedRatingAge(13)
                .pinHash("encoded-pin")
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profile.getId()).build());

    service.setKind(
        SetProfileKindCommand.builder()
            .actingAccountId(managerId)
            .profileId(profile.getId())
            .kind(ProfileKind.KID)
            .build());

    assertThat(profile.getKind()).isEqualTo(ProfileKind.KID);
    assertThat(profile.getMaximumAllowedRatingAge()).isEqualTo(13);
    assertThat(profile.getPinHash()).isEqualTo("encoded-pin");
    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.PROFILE_KIND_CHANGED);
  }

  @Test
  @DisplayName("Should set content ceiling on adult profile without changing kind or PIN")
  void shouldSetContentCeilingOnAdultProfileWithoutChangingKindOrPin() {
    var managerId = UUID.randomUUID();
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Restricted Adult")
                .kind(ProfileKind.ADULT)
                .pinHash("encoded-pin")
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profile.getId()).build());

    service.setContentCeiling(
        SetProfileContentCeilingCommand.builder()
            .actingAccountId(managerId)
            .profileId(profile.getId())
            .maximumAllowedRatingAge(13)
            .build());

    assertThat(profile.getKind()).isEqualTo(ProfileKind.ADULT);
    assertThat(profile.getMaximumAllowedRatingAge()).isEqualTo(13);
    assertThat(profile.getPinHash()).isEqualTo("encoded-pin");
    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.PROFILE_CONTENT_CEILING_SET);
  }

  @Test
  @DisplayName("Should remove content ceiling without changing profile kind or PIN")
  void shouldRemoveContentCeilingWithoutChangingProfileKindOrPin() {
    var managerId = UUID.randomUUID();
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Restricted Adult")
                .kind(ProfileKind.ADULT)
                .maximumAllowedRatingAge(13)
                .pinHash("encoded-pin")
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profile.getId()).build());

    service.removeContentCeiling(
        RemoveProfileContentCeilingCommand.builder()
            .actingAccountId(managerId)
            .profileId(profile.getId())
            .build());

    assertThat(profile.getKind()).isEqualTo(ProfileKind.ADULT);
    assertThat(profile.getMaximumAllowedRatingAge()).isNull();
    assertThat(profile.getPinHash()).isEqualTo("encoded-pin");
    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.PROFILE_CONTENT_CEILING_REMOVED);
  }

  @Test
  @DisplayName("Should reset profile PIN without changing kind or content ceiling")
  void shouldResetProfilePinWithoutChangingKindOrContentCeiling() {
    var managerId = UUID.randomUUID();
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Managed Profile")
                .kind(ProfileKind.ADULT)
                .maximumAllowedRatingAge(13)
                .pinHash("old-pin-hash")
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profile.getId()).build());

    service.resetPin(
        ResetProfilePinCommand.builder()
            .actingAccountId(managerId)
            .profileId(profile.getId())
            .pinHash("new-pin-hash")
            .build());

    assertThat(profile.getKind()).isEqualTo(ProfileKind.ADULT);
    assertThat(profile.getMaximumAllowedRatingAge()).isEqualTo(13);
    assertThat(profile.getPinHash()).isEqualTo("new-pin-hash");
    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.PROFILE_PIN_RESET);
  }

  @Test
  @DisplayName("Should reject kid kind without a local parent manager in every household")
  void shouldRejectKidKindWithoutLocalParentManagerInEveryHousehold() {
    var householdId = UUID.randomUUID();
    var manager =
        accountRepository.save(
            UserAccount.builder()
                .email("remote-" + UUID.randomUUID() + "@example.com")
                .displayName("Remote Manager")
                .passwordHash("encoded")
                .accountRole(AccountRole.USER)
                .homeHouseholdId(UUID.randomUUID())
                .householdRole(HouseholdRole.PARENT)
                .build());
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Changing Profile")
                .kind(ProfileKind.ADULT)
                .maximumAllowedRatingAge(7)
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(manager.getId()).profileId(profile.getId()).build());
    share(profile, householdId);
    var command =
        SetProfileKindCommand.builder()
            .actingAccountId(manager.getId())
            .profileId(profile.getId())
            .kind(ProfileKind.KID)
            .build();

    assertThatThrownBy(() -> service.setKind(command))
        .isInstanceOf(KidProfileManagerRequiredException.class);

    assertThat(profile.getKind()).isEqualTo(ProfileKind.ADULT);
    assertThat(auditRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should treat blank adult PIN as absent beside kid profile")
  void shouldTreatBlankAdultPinAsAbsentBesideKidProfile() {
    var householdId = UUID.randomUUID();
    var adult =
        profileRepository.save(
            Profile.builder().name("Adult").kind(ProfileKind.ADULT).pinHash(" ").build());
    var kid =
        profileRepository.save(
            Profile.builder().name("Kid").kind(ProfileKind.KID).maximumAllowedRatingAge(7).build());
    share(adult, householdId);
    share(kid, householdId);

    assertThatThrownBy(() -> safetyService.validateActivation(adult, householdId))
        .isInstanceOf(ProfileSafetyViolationException.class);
  }

  @Test
  @DisplayName("Should require PIN for unrestricted kid beside more restricted kid")
  void shouldRequirePinForUnrestrictedKidBesideMoreRestrictedKid() {
    var householdId = UUID.randomUUID();
    var unrestrictedKid =
        profileRepository.save(
            Profile.builder().name("Unrestricted Kid").kind(ProfileKind.KID).build());
    var restrictedKid =
        profileRepository.save(
            Profile.builder()
                .name("Restricted Kid")
                .kind(ProfileKind.KID)
                .maximumAllowedRatingAge(7)
                .build());
    share(unrestrictedKid, householdId);
    share(restrictedKid, householdId);

    assertThatThrownBy(() -> safetyService.validateActivation(unrestrictedKid, householdId))
        .isInstanceOfSatisfying(
            ProfileSafetyViolationException.class,
            exception ->
                assertThat(exception.profilesRequiringPin())
                    .containsExactly(unrestrictedKid.getId()));
  }

  @Test
  @DisplayName("Should require PIN for less restricted kid beside more restricted kid")
  void shouldRequirePinForLessRestrictedKidBesideMoreRestrictedKid() {
    var householdId = UUID.randomUUID();
    var olderKid =
        profileRepository.save(
            Profile.builder()
                .name("Older Kid")
                .kind(ProfileKind.KID)
                .maximumAllowedRatingAge(13)
                .build());
    var youngerKid =
        profileRepository.save(
            Profile.builder()
                .name("Younger Kid")
                .kind(ProfileKind.KID)
                .maximumAllowedRatingAge(7)
                .build());
    share(olderKid, householdId);
    share(youngerKid, householdId);

    assertThatThrownBy(() -> safetyService.validateActivation(olderKid, householdId))
        .isInstanceOfSatisfying(
            ProfileSafetyViolationException.class,
            exception ->
                assertThat(exception.profilesRequiringPin()).containsExactly(olderKid.getId()));
  }

  @Test
  @DisplayName("Should allow equally unrestricted kid profiles without PIN")
  void shouldAllowEquallyUnrestrictedKidProfilesWithoutPin() {
    var householdId = UUID.randomUUID();
    var firstKid =
        profileRepository.save(Profile.builder().name("First Kid").kind(ProfileKind.KID).build());
    var secondKid =
        profileRepository.save(Profile.builder().name("Second Kid").kind(ProfileKind.KID).build());
    share(firstKid, householdId);
    share(secondKid, householdId);

    assertThatCode(() -> safetyService.validateActivation(firstKid, householdId))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should allow restricted kid beside PIN-protected unrestricted kid")
  void shouldAllowRestrictedKidBesidePinProtectedUnrestrictedKid() {
    var householdId = UUID.randomUUID();
    var unrestrictedKid =
        profileRepository.save(
            Profile.builder()
                .name("Unrestricted Kid")
                .kind(ProfileKind.KID)
                .pinHash("encoded-pin")
                .build());
    var restrictedKid =
        profileRepository.save(
            Profile.builder()
                .name("Restricted Kid")
                .kind(ProfileKind.KID)
                .maximumAllowedRatingAge(13)
                .build());
    share(unrestrictedKid, householdId);
    share(restrictedKid, householdId);

    assertThatCode(() -> safetyService.validateActivation(restrictedKid, householdId))
        .doesNotThrowAnyException();
  }

  private void share(Profile profile, UUID householdId) {
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
  }
}
