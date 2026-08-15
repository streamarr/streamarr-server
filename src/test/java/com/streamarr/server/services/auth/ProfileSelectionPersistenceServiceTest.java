package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Selection Persistence Service Tests")
class ProfileSelectionPersistenceServiceTest {

  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final ProfileAvailabilityService profileAvailabilityService =
      new ProfileAvailabilityService(accountRepository, shareRepository, profileRepository);
  private final ProfileSelectionPersistenceService service =
      new ProfileSelectionPersistenceService(sessionRepository, profileAvailabilityService);

  @Test
  @DisplayName("Should persist profile selection when session is live for account")
  void shouldPersistProfileSelectionWhenSessionIsLiveForAccount() {
    var householdId = UUID.randomUUID();
    var account = saveAccount(householdId);
    var profile = profileRepository.save(Profile.builder().name("Selectable").build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());

    var selected = service.select(account.getId(), session.getId(), profile.getId());

    assertThat(selected.getActiveProfileId()).isEqualTo(profile.getId());
    assertThat(sessionRepository.findById(session.getId()).orElseThrow().getActiveProfileId())
        .isEqualTo(profile.getId());
  }

  @Test
  @DisplayName("Should reject profile selection when locked session is not live for account")
  void shouldRejectProfileSelectionWhenLockedSessionIsNotLiveForAccount() {
    var accountId = UUID.randomUUID();
    var otherAccountSession =
        sessionRepository.save(AuthSession.builder().accountId(UUID.randomUUID()).build());
    var revokedSession =
        sessionRepository.save(
            AuthSession.builder().accountId(accountId).revokedAt(Instant.EPOCH).build());
    var profileId = UUID.randomUUID();
    var missingSessionId = UUID.randomUUID();
    var otherAccountSessionId = otherAccountSession.getId();
    var revokedSessionId = revokedSession.getId();

    assertThatThrownBy(() -> service.select(accountId, missingSessionId, profileId))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(() -> service.select(accountId, otherAccountSessionId, profileId))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(() -> service.select(accountId, revokedSessionId, profileId))
        .isInstanceOf(AuthenticationRequiredException.class);

    assertThat(otherAccountSession.getActiveProfileId()).isNull();
    assertThat(revokedSession.getActiveProfileId()).isNull();
  }

  private UserAccount saveAccount(UUID householdId) {
    return accountRepository.save(
        UserAccount.builder()
            .email("selection-" + UUID.randomUUID() + "@example.com")
            .displayName("Selection Account")
            .passwordHash("encoded")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(householdId)
            .householdRole(HouseholdRole.MEMBER)
            .build());
  }
}
