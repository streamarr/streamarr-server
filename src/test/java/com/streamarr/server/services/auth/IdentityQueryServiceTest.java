package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Identity Query Service Tests")
class IdentityQueryServiceTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final ProfileAvailabilityService availabilityService =
      new ProfileAvailabilityService(shareRepository, profileRepository);
  private final IdentityQueryService service =
      new IdentityQueryService(accountRepository, availabilityService);

  @Test
  @DisplayName("Should return selectable profiles as a flat account result")
  void shouldReturnSelectableProfilesAsAFlatAccountResult() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var profile = profileRepository.save(Profile.builder().name("Portable Profile").build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(homeHouseholdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(account.getId())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .householdId(account.getHomeHouseholdId())
            .householdRole(account.getHouseholdRole())
            .profileId(profile.getId())
            .build();

    var view = service.meView(identity);

    assertThat(view.profiles())
        .singleElement()
        .satisfies(
            selectable -> {
              assertThat(selectable.id()).isEqualTo(profile.getId());
              assertThat(selectable.active()).isTrue();
            });
  }

  @Test
  @DisplayName("Should return account scope with no selectable profiles")
  void shouldReturnAccountScopeWithNoSelectableProfiles() {
    var account = saveAccount(UUID.randomUUID());
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(account.getId())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .householdId(account.getHomeHouseholdId())
            .householdRole(account.getHouseholdRole())
            .build();

    var view = service.meView(identity);

    assertThat(view.account()).isEqualTo(account);
    assertThat(view.authority().scope()).isEqualTo(TokenScope.ACCOUNT);
    assertThat(view.profiles()).isEmpty();
  }

  @Test
  @DisplayName("Should reject identity query when account no longer exists")
  void shouldRejectIdentityQueryWhenAccountNoLongerExists() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .householdId(UUID.randomUUID())
            .householdRole(HouseholdRole.MEMBER)
            .build();

    assertThatThrownBy(() -> service.meView(identity))
        .isInstanceOf(AuthenticationRequiredException.class);
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
}
