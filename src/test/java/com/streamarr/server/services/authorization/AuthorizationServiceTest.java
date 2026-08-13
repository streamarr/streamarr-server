package com.streamarr.server.services.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("UnitTest")
@DisplayName("Authorization Service Tests")
class AuthorizationServiceTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final RequestAuthorizationStateResolver stateResolver =
      new RequestAuthorizationStateResolver(accountRepository, sessionRepository, shareRepository);
  private final AuthorizationService authorizationService =
      new SecurityContextAuthorizationService(stateResolver, shareRepository);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should fail profile access closed when signed profile is no longer shared")
  void shouldFailProfileAccessClosedWhenSignedProfileIsNoLongerShared() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    authenticate(account, session, UUID.randomUUID());

    assertThat(authorizationService.requireHousehold()).isEqualTo(homeHouseholdId);
    assertThatThrownBy(authorizationService::requireProfile)
        .isInstanceOf(ProfileRequiredException.class);
  }

  @Test
  @DisplayName("Should build playback authority from live home and active share")
  void shouldBuildPlaybackAuthorityFromLiveHomeAndActiveShare() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    var profileId = UUID.randomUUID();
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(homeHouseholdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    authenticate(account, session, profileId);

    var authority = authorizationService.requirePlaybackAuthority();

    assertThat(authority.authSessionId()).isEqualTo(session.getId());
    assertThat(authority.householdId()).isEqualTo(homeHouseholdId);
    assertThat(authority.profileId()).isEqualTo(profileId);
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

  private void authenticate(UserAccount account, AuthSession session, UUID profileId) {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(account.getId())
            .role(AccountRole.USER)
            .authSessionId(session.getId())
            .scope(TokenScope.PROFILE)
            .profileId(profileId)
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new StreamarrAuthenticationToken(
                identity,
                null,
                List.of(new SimpleGrantedAuthority(TokenScope.PROFILE.authority()))));
  }
}
