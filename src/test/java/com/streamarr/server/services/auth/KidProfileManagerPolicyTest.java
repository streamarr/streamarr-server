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
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
  private final TrackingUserAccountRepository accountRepository =
      new TrackingUserAccountRepository();
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

    var kidId = kid.getId();
    var localParentId = localParent.getId();

    assertThatThrownBy(() -> policy.validateManagerRemoval(kidId, localParentId))
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
  @DisplayName("Should ignore manager removal policy for adult profile")
  void shouldIgnoreManagerRemovalPolicyForAdultProfile() {
    var adult =
        profileRepository.save(
            Profile.builder().name("Adult").classification(ProfileClassification.ADULT).build());

    assertThatCode(() -> policy.validateManagerRemoval(adult.getId(), UUID.randomUUID()))
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

    var localParentId = localParent.getId();

    assertThatThrownBy(() -> policy.validateAccountDeparture(localParentId, householdId))
        .isInstanceOf(KidProfileManagerRequiredException.class);
  }

  @Test
  @DisplayName("Should allow account departure when another local parent manager remains")
  void shouldAllowAccountDepartureWhenAnotherLocalParentManagerRemains() {
    var householdId = UUID.randomUUID();
    var departingParent = saveAccount(householdId, HouseholdRole.PARENT);
    var remainingParent = saveAccount(householdId, HouseholdRole.PARENT);
    var kid = saveActiveKid(householdId);
    manage(departingParent, kid);
    manage(remainingParent, kid);

    assertThatCode(() -> policy.validateAccountDeparture(departingParent.getId(), householdId))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should ignore account departure for adult and inactive kid management")
  void shouldIgnoreAccountDepartureForAdultAndInactiveKidManagement() {
    var householdId = UUID.randomUUID();
    var account = saveAccount(householdId, HouseholdRole.PARENT);
    var adult =
        profileRepository.save(
            Profile.builder().name("Adult").classification(ProfileClassification.ADULT).build());
    var inactiveKid =
        profileRepository.save(
            Profile.builder()
                .name("Inactive Kid")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .build());
    manage(account, adult);
    manage(account, inactiveKid);

    assertThatCode(() -> policy.validateAccountDeparture(account.getId(), householdId))
        .doesNotThrowAnyException();
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

    var profileId = profile.getId();

    assertThatThrownBy(() -> policy.validateKidClassification(profileId))
        .isInstanceOf(KidProfileManagerRequiredException.class);
  }

  @Test
  @DisplayName("Should allow kid classification when every active home has local parent manager")
  void shouldAllowKidClassificationWhenEveryActiveHomeHasLocalParentManager() {
    var householdId = UUID.randomUUID();
    var parent = saveAccount(householdId, HouseholdRole.PARENT);
    var profile =
        profileRepository.save(
            Profile.builder()
                .name("Becoming Kid")
                .classification(ProfileClassification.ADULT)
                .build());
    manage(parent, profile);
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());

    assertThatCode(() -> policy.validateKidClassification(profile.getId()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should not require local manager when activating adult share")
  void shouldNotRequireLocalManagerWhenActivatingAdultShare() {
    var adult =
        profileRepository.save(
            Profile.builder().name("Adult").classification(ProfileClassification.ADULT).build());

    assertThatCode(() -> policy.validateShareActivation(adult.getId(), UUID.randomUUID()))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should reject kid share activation when local manager is household member")
  void shouldRejectKidShareActivationWhenLocalManagerIsHouseholdMember() {
    var householdId = UUID.randomUUID();
    var member = saveAccount(householdId, HouseholdRole.MEMBER);
    var kid =
        profileRepository.save(
            Profile.builder()
                .name("Kid")
                .classification(ProfileClassification.KID)
                .maximumAllowedRatingAge(7)
                .build());
    manage(member, kid);

    var kidId = kid.getId();

    assertThatThrownBy(() -> policy.validateShareActivation(kidId, householdId))
        .isInstanceOf(KidProfileManagerRequiredException.class);
  }

  @Test
  @DisplayName("Should load profile managers in one bulk account query")
  void shouldLoadProfileManagersInOneBulkAccountQuery() {
    var householdId = UUID.randomUUID();
    var kid = saveActiveKid(householdId);
    for (var index = 0; index < 3; index++) {
      manage(saveAccount(UUID.randomUUID(), HouseholdRole.PARENT), kid);
    }

    assertThatThrownBy(() -> policy.validateKidClassification(kid.getId()))
        .isInstanceOf(KidProfileManagerRequiredException.class);

    org.assertj.core.api.Assertions.assertThat(accountRepository.individualLookups).isZero();
    org.assertj.core.api.Assertions.assertThat(accountRepository.bulkLookups).isOne();
  }

  @Test
  @DisplayName("Should return domain denial when manager removal profile is missing")
  void shouldReturnDomainDenialWhenManagerRemovalProfileIsMissing() {
    assertThatThrownBy(() -> policy.validateManagerRemoval(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should return domain denial when share activation profile is missing")
  void shouldReturnDomainDenialWhenShareActivationProfileIsMissing() {
    assertThatThrownBy(() -> policy.validateShareActivation(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(ProfileAccessDeniedException.class);
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

  private static final class TrackingUserAccountRepository extends FakeUserAccountRepository {

    private int individualLookups;
    private int bulkLookups;

    @Override
    public Optional<UserAccount> findById(UUID id) {
      individualLookups++;
      return super.findById(id);
    }

    @Override
    public List<UserAccount> findAllById(Iterable<UUID> ids) {
      bulkLookups++;
      var accounts = new ArrayList<UserAccount>();
      ids.forEach(id -> Optional.ofNullable(database.get(id)).ifPresent(accounts::add));
      return accounts;
    }
  }
}
