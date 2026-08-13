package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Session Scope Service Tests")
class SessionScopeServiceTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final ProfileAvailabilityService availabilityService =
      new ProfileAvailabilityService(accountRepository, shareRepository, profileRepository);
  private final SessionScopeService service =
      new SessionScopeService(
          availabilityService, sessionRepository, accountRepository, Clock.systemUTC());

  @Test
  @DisplayName("Should select profile shared into account home without household selection")
  void shouldSelectProfileSharedIntoAccountHomeWithoutHouseholdSelection() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var profile = profileRepository.save(Profile.builder().name("Portable Profile").build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(homeHouseholdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());

    var context = service.selectProfile(account.getId(), session.getId(), profile.getId());

    assertThat(context.profileId()).isEqualTo(profile.getId());
    assertThat(sessionRepository.findById(session.getId()).orElseThrow().getActiveProfileId())
        .isEqualTo(profile.getId());
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
