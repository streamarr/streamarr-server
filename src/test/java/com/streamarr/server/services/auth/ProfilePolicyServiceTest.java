package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
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
          shareRepository,
          safetyService,
          new KidProfileManagerPolicy(
              profileRepository, managerRepository, shareRepository, accountRepository),
          new SecurityAuditService(auditRepository));

  @Test
  @DisplayName("Should redact profile PIN hash from policy command diagnostics")
  void shouldRedactProfilePinHashFromPolicyCommandDiagnostics() {
    var command =
        ProfilePolicyChange.builder()
            .actingAccountId(UUID.randomUUID())
            .profileId(UUID.randomUUID())
            .classification(ProfileClassification.ADULT)
            .pinHash("sensitive-pin-hash")
            .build();

    assertThat(command.toString()).doesNotContain("sensitive-pin-hash").contains("<redacted>");
  }

  @Test
  @DisplayName("Should reject removing adult PIN while kid is active in a shared household")
  void shouldRejectRemovingAdultPinWhileKidIsActiveInSharedHousehold() {
    var householdId = UUID.randomUUID();
    var managerId = UUID.randomUUID();
    var adult =
        profileRepository.save(
            Profile.builder()
                .name("Protected Adult")
                .classification(ProfileClassification.ADULT)
                .pinHash("encoded-pin")
                .build());
    var kid =
        profileRepository.save(
            Profile.builder()
                .name("Restricted Kid")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(adult.getId()).build());
    share(adult, householdId);
    share(kid, householdId);

    assertThatThrownBy(
            () ->
                service.changePolicy(
                    ProfilePolicyChange.builder()
                        .actingAccountId(managerId)
                        .profileId(adult.getId())
                        .classification(ProfileClassification.ADULT)
                        .pinHash(null)
                        .build()))
        .isInstanceOf(ProfileSafetyViolationException.class)
        .satisfies(
            exception ->
                assertThat(((ProfileSafetyViolationException) exception).profilesRequiringPin())
                    .containsExactly(adult.getId()));

    assertThat(adult.getPinHash()).isEqualTo("encoded-pin");
  }

  @Test
  @DisplayName("Should reject kid classification without a local parent manager in every household")
  void shouldRejectKidClassificationWithoutLocalParentManagerInEveryHousehold() {
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
                .classification(ProfileClassification.ADULT)
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(manager.getId()).profileId(profile.getId()).build());
    share(profile, householdId);

    assertThatThrownBy(
            () ->
                service.changePolicy(
                    ProfilePolicyChange.builder()
                        .actingAccountId(manager.getId())
                        .profileId(profile.getId())
                        .classification(ProfileClassification.KID)
                        .maximumAllowedRatingAge(7)
                        .build()))
        .isInstanceOf(KidProfileManagerRequiredException.class);

    assertThat(profile.getClassification()).isEqualTo(ProfileClassification.ADULT);
    assertThat(auditRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should audit a successful profile policy change")
  void shouldAuditSuccessfulProfilePolicyChange() {
    var managerId = UUID.randomUUID();
    var profile = profileRepository.save(Profile.builder().name("Adult").build());
    managerRepository.save(
        ProfileManager.builder().accountId(managerId).profileId(profile.getId()).build());

    service.changePolicy(
        ProfilePolicyChange.builder()
            .actingAccountId(managerId)
            .profileId(profile.getId())
            .classification(ProfileClassification.ADULT)
            .pinHash("new-pin")
            .build());

    assertThat(auditRepository.findAll())
        .singleElement()
        .extracting(event -> event.getOperation())
        .isEqualTo(SecurityAuditOperation.PROFILE_POLICY_CHANGED);
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
