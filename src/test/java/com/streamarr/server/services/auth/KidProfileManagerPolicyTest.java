package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Kid Profile Manager Policy Tests")
class KidProfileManagerPolicyTest {

  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeProfileManagerRepository managerRepository = new FakeProfileManagerRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final KidProfileManagerPolicy policy =
      new KidProfileManagerPolicy(
          profileRepository, managerRepository, shareRepository, accountRepository);

  @Test
  @DisplayName(
      "Should reject manager removal when it leaves an active kid share without local parent")
  void shouldRejectManagerRemovalWhenItLeavesActiveKidShareWithoutLocalParent() {
    var localHouseholdId = UUID.randomUUID();
    var localParent = saveAccount(localHouseholdId, HouseholdRole.PARENT);
    var remoteParent = saveAccount(UUID.randomUUID(), HouseholdRole.PARENT);
    var kid = saveActiveKid(localHouseholdId);
    manage(localParent, kid);
    manage(remoteParent, kid);

    assertThatThrownBy(() -> policy.validateManagerRemoval(kid.getId(), localParent.getId()))
        .isInstanceOf(KidProfileManagerRequiredException.class);
  }

  @Test
  @DisplayName("Should allow manager removal when another local parent remains")
  void shouldAllowManagerRemovalWhenAnotherLocalParentRemains() {
    var householdId = UUID.randomUUID();
    var firstParent = saveAccount(householdId, HouseholdRole.PARENT);
    var secondParent = saveAccount(householdId, HouseholdRole.OWNER);
    var kid = saveActiveKid(householdId);
    manage(firstParent, kid);
    manage(secondParent, kid);

    assertThatCode(() -> policy.validateManagerRemoval(kid.getId(), firstParent.getId()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "Should reject account departure when it removes the active kid local parent manager")
  void shouldRejectAccountDepartureWhenItRemovesActiveKidLocalParentManager() {
    var householdId = UUID.randomUUID();
    var localParent = saveAccount(householdId, HouseholdRole.PARENT);
    var kid = saveActiveKid(householdId);
    manage(localParent, kid);

    assertThatThrownBy(() -> policy.validateAccountDeparture(localParent.getId(), householdId))
        .isInstanceOf(KidProfileManagerRequiredException.class);
  }

  @Test
  @DisplayName(
      "Should reject kid classification when a household receiving the profile has no local parent manager")
  void shouldRejectKidClassificationWhenReceivingHouseholdHasNoLocalParentManager() {
    var householdId = UUID.randomUUID();
    var remoteParent = saveAccount(UUID.randomUUID(), HouseholdRole.PARENT);
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Becoming Kid")
                .classification(ProfileClassification.ADULT)
                .build());
    manage(remoteParent, profile);
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());

    assertThatThrownBy(() -> policy.validateKidClassification(profile.getId()))
        .isInstanceOf(KidProfileManagerRequiredException.class);
  }

  private Profile saveActiveKid(UUID householdId) {
    var kid =
        profileRepository.save(
            Profile.builder()
                .name("Kid")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(kid.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    return kid;
  }

  private UserAccount saveAccount(UUID householdId, HouseholdRole role) {
    return accountRepository.save(
        UserAccount.builder()
            .email("parent-" + UUID.randomUUID() + "@example.com")
            .displayName("Parent")
            .passwordHash("encoded")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(householdId)
            .householdRole(role)
            .build());
  }

  private void manage(UserAccount account, Profile profile) {
    managerRepository.save(
        ProfileManager.builder().accountId(account.getId()).profileId(profile.getId()).build());
  }
}
