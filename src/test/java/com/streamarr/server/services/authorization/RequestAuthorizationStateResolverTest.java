package com.streamarr.server.services.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("UnitTest")
@DisplayName("Request Authorization State Resolver Tests")
class RequestAuthorizationStateResolverTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final RequestAuthorizationStateResolver resolver =
      new RequestAuthorizationStateResolver(accountRepository, sessionRepository, shareRepository);

  @Test
  @DisplayName("Should clear profile authority when signed profile is not shared into current home")
  void shouldClearProfileAuthorityWhenSignedProfileIsNotSharedIntoCurrentHome() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    var signed =
        AuthenticatedIdentity.builder()
            .accountId(account.getId())
            .role(AccountRole.USER)
            .authSessionId(session.getId())
            .scope(TokenScope.PROFILE)
            .profileId(UUID.randomUUID())
            .build();

    var state = resolver.resolve(authentication(signed));

    assertThat(state.account().getHomeHouseholdId()).isEqualTo(homeHouseholdId);
    assertThat(state.account().getHouseholdRole()).isEqualTo(HouseholdRole.PARENT);
    assertThat(state.activeProfileId()).isNull();
  }

  @Test
  @DisplayName("Should cache live authorization state only for one authentication token")
  void shouldCacheLiveAuthorizationStateOnlyForOneAuthenticationToken() {
    var initialHouseholdId = UUID.randomUUID();
    var account = saveAccount(initialHouseholdId);
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    var signed =
        AuthenticatedIdentity.builder()
            .accountId(account.getId())
            .role(AccountRole.USER)
            .authSessionId(session.getId())
            .scope(TokenScope.ACCOUNT)
            .build();
    var firstRequest = authentication(signed);

    assertThat(resolver.resolve(firstRequest).account().getHomeHouseholdId())
        .isEqualTo(initialHouseholdId);

    var newHouseholdId = UUID.randomUUID();
    accountRepository.save(account.toBuilder().homeHouseholdId(newHouseholdId).build());

    assertThat(resolver.resolve(firstRequest).account().getHomeHouseholdId())
        .isEqualTo(initialHouseholdId);
    assertThat(resolver.resolve(authentication(signed)).account().getHomeHouseholdId())
        .isEqualTo(newHouseholdId);
  }

  private StreamarrAuthenticationToken authentication(AuthenticatedIdentity identity) {
    var jwt =
        Jwt.withTokenValue("test-token-" + UUID.randomUUID())
            .header("alg", "none")
            .subject(identity.accountId().toString())
            .build();
    return new StreamarrAuthenticationToken(identity, jwt, List.of());
  }

  private UserAccount saveAccount(UUID homeHouseholdId) {
    return accountRepository.save(
        UserAccount.builder()
            .email("parent-" + UUID.randomUUID() + "@example.com")
            .displayName("Parent")
            .passwordHash("{noop}not-a-real-hash")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(homeHouseholdId)
            .householdRole(HouseholdRole.PARENT)
            .build());
  }
}
