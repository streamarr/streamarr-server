package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
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
@DisplayName("Profile Availability Service Tests")
class ProfileAvailabilityServiceTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final TrackingShareRepository shareRepository = new TrackingShareRepository();
  private final TrackingProfileRepository profileRepository = new TrackingProfileRepository();

  private final ProfileAvailabilityService service =
      new ProfileAvailabilityService(shareRepository, profileRepository);

  @Test
  @DisplayName("Should return only active profiles shared into account home")
  void shouldReturnOnlyActiveProfilesSharedIntoAccountHome() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var active = saveProfile("Active Profile");
    active.setPinHash("encoded-pin");
    var pending = saveProfile("Pending Profile");
    var remote = saveProfile("Remote Profile");
    share(active, homeHouseholdId, ProfileShareStatus.ACTIVE);
    share(pending, homeHouseholdId, ProfileShareStatus.PENDING);
    share(remote, UUID.randomUUID(), ProfileShareStatus.ACTIVE);

    var selectable = service.selectableProfiles(account, active.getId());

    assertThat(selectable)
        .singleElement()
        .satisfies(
            profile -> {
              assertThat(profile.id()).isEqualTo(active.getId());
              assertThat(profile.name()).isEqualTo("Active Profile");
              assertThat(profile.active()).isTrue();
              assertThat(profile.pinProtected()).isTrue();
            });
  }

  @Test
  @DisplayName("Should reject pending profile when selected")
  void shouldRejectPendingProfileWhenSelected() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var pending = saveProfile("Pending Profile");
    share(pending, homeHouseholdId, ProfileShareStatus.PENDING);
    var pendingProfileId = pending.getId();

    assertThatThrownBy(() -> service.requireSelectableProfile(account, pendingProfileId))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should load selectable profiles in one bulk repository query")
  void shouldLoadSelectableProfilesInOneBulkRepositoryQuery() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    for (var index = 0; index < 3; index++) {
      share(saveProfile("Profile " + index), homeHouseholdId, ProfileShareStatus.ACTIVE);
    }

    service.selectableProfiles(account, null);

    assertThat(profileRepository.individualLookups).isZero();
    assertThat(profileRepository.bulkLookups).isOne();
  }

  @Test
  @DisplayName("Should check one active share when profile is selected")
  void shouldCheckOneActiveShareWhenProfileIsSelected() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var profile = saveProfile("Selected Profile");
    share(profile, homeHouseholdId, ProfileShareStatus.ACTIVE);

    assertThat(service.requireSelectableProfile(account, profile.getId())).isEqualTo(profile);

    assertThat(shareRepository.householdListLookups).isZero();
    assertThat(shareRepository.existenceLookups).isOne();
  }

  private UserAccount saveAccount(UUID homeHouseholdId) {
    return accountRepository.save(
        UserAccount.builder()
            .email("viewer-" + UUID.randomUUID() + "@example.com")
            .displayName("Viewer")
            .passwordHash("{noop}not-a-real-hash")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(homeHouseholdId)
            .householdRole(HouseholdRole.MEMBER)
            .build());
  }

  private Profile saveProfile(String name) {
    return profileRepository.save(Profile.builder().name(name).build());
  }

  private void share(Profile profile, UUID householdId, ProfileShareStatus status) {
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(status)
            .build());
  }

  private static final class TrackingProfileRepository extends FakeProfileRepository {

    private int individualLookups;
    private int bulkLookups;

    @Override
    public Optional<Profile> findById(UUID id) {
      individualLookups++;
      return super.findById(id);
    }

    @Override
    public List<Profile> findAllById(Iterable<UUID> ids) {
      bulkLookups++;
      var profiles = new ArrayList<Profile>();
      ids.forEach(id -> Optional.ofNullable(database.get(id)).ifPresent(profiles::add));
      return profiles;
    }
  }

  private static final class TrackingShareRepository extends FakeProfileHouseholdShareRepository {

    private int householdListLookups;
    private int existenceLookups;

    @Override
    public List<ProfileHouseholdShare> findByHouseholdIdAndStatus(
        UUID householdId, ProfileShareStatus status) {
      householdListLookups++;
      return super.findByHouseholdIdAndStatus(householdId, status);
    }

    @Override
    public boolean existsByProfileIdAndHouseholdIdAndStatus(
        UUID profileId, UUID householdId, ProfileShareStatus status) {
      existenceLookups++;
      return super.existsByProfileIdAndHouseholdIdAndStatus(profileId, householdId, status);
    }
  }
}
