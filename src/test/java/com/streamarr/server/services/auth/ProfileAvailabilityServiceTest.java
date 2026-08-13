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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Availability Service Tests")
class ProfileAvailabilityServiceTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();

  private final ProfileAvailabilityService service =
      new ProfileAvailabilityService(accountRepository, shareRepository, profileRepository);

  @Test
  @DisplayName("Should return only active profiles shared into account home")
  void shouldReturnOnlyActiveProfilesSharedIntoAccountHome() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var active = saveProfile("Active Profile");
    var pending = saveProfile("Pending Profile");
    var remote = saveProfile("Remote Profile");
    share(active, homeHouseholdId, ProfileShareStatus.ACTIVE);
    share(pending, homeHouseholdId, ProfileShareStatus.PENDING);
    share(remote, UUID.randomUUID(), ProfileShareStatus.ACTIVE);

    var selectable = service.selectableProfiles(account.getId(), active.getId());

    assertThat(selectable)
        .singleElement()
        .satisfies(
            profile -> {
              assertThat(profile.id()).isEqualTo(active.getId());
              assertThat(profile.name()).isEqualTo("Active Profile");
              assertThat(profile.active()).isTrue();
            });
  }

  @Test
  @DisplayName("Should reject pending profile when selected")
  void shouldRejectPendingProfileWhenSelected() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var pending = saveProfile("Pending Profile");
    share(pending, homeHouseholdId, ProfileShareStatus.PENDING);

    assertThatThrownBy(() -> service.requireSelectableProfile(account.getId(), pending.getId()))
        .isInstanceOf(ProfileAccessDeniedException.class);
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
}
