package com.streamarr.server.services.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
      new RequestAuthorizationStateResolver(
          accountRepository, sessionRepository, shareRepository, new MutexFactoryProvider());

  @Test
  @DisplayName(
      "Should downgrade profile authority when signed profile is not shared into current home")
  void shouldDowngradeProfileAuthorityWhenSignedProfileIsNotSharedIntoCurrentHome() {
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

  @Test
  @DisplayName("Should not mutate authentication mutex key while resolving authorization state")
  void shouldNotMutateAuthenticationMutexKeyWhileResolvingAuthorizationState() {
    var account = saveAccount(UUID.randomUUID());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    var signed =
        AuthenticatedIdentity.builder()
            .accountId(account.getId())
            .role(AccountRole.USER)
            .authSessionId(session.getId())
            .scope(TokenScope.ACCOUNT)
            .build();
    var authentication = authentication(signed);
    var mutexKeyHash = authentication.hashCode();

    resolver.resolve(authentication);

    assertThat(authentication.hashCode()).isEqualTo(mutexKeyHash);
  }

  @Test
  @DisplayName("Should reject authorization state for revoked session")
  void shouldRejectAuthorizationStateForRevokedSession() {
    var account = saveAccount(UUID.randomUUID());
    var session =
        sessionRepository.save(
            AuthSession.builder().accountId(account.getId()).revokedAt(Instant.EPOCH).build());
    var signed =
        AuthenticatedIdentity.builder()
            .accountId(account.getId())
            .role(AccountRole.USER)
            .authSessionId(session.getId())
            .scope(TokenScope.ACCOUNT)
            .build();
    var requestAuthentication = authentication(signed);

    assertThatThrownBy(() -> resolver.resolve(requestAuthentication))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should resolve while another caller holds the authentication monitor")
  void shouldResolveWhileAnotherCallerHoldsAuthenticationMonitor() throws Exception {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    var signed =
        AuthenticatedIdentity.builder()
            .accountId(account.getId())
            .role(AccountRole.USER)
            .authSessionId(session.getId())
            .scope(TokenScope.ACCOUNT)
            .build();
    var authentication = authentication(signed);
    var started = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      synchronized (authentication) {
        var resolution =
            executor.submit(
                () -> {
                  started.countDown();
                  return resolver.resolve(authentication);
                });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(resolution.get(1, TimeUnit.SECONDS).account().getHomeHouseholdId())
            .isEqualTo(homeHouseholdId);
      }
    }
  }

  @Test
  @DisplayName("Should resolve separate requests for one session concurrently")
  void shouldResolveSeparateRequestsForOneSessionConcurrently() throws Exception {
    var blockingAccountRepository = new BlockingUserAccountRepository();
    var concurrentResolver =
        new RequestAuthorizationStateResolver(
            blockingAccountRepository,
            sessionRepository,
            shareRepository,
            new MutexFactoryProvider());
    var account =
        blockingAccountRepository.save(
            UserAccount.builder()
                .email("concurrent-" + UUID.randomUUID() + "@example.com")
                .displayName("Concurrent Parent")
                .passwordHash("{noop}not-a-real-hash")
                .accountRole(AccountRole.USER)
                .homeHouseholdId(UUID.randomUUID())
                .householdRole(HouseholdRole.PARENT)
                .build());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    var signed =
        AuthenticatedIdentity.builder()
            .accountId(account.getId())
            .role(AccountRole.USER)
            .authSessionId(session.getId())
            .scope(TokenScope.ACCOUNT)
            .build();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> concurrentResolver.resolve(authentication(signed)));
      var second = executor.submit(() -> concurrentResolver.resolve(authentication(signed)));
      var bothReadsStarted = blockingAccountRepository.awaitBothReads();
      blockingAccountRepository.releaseReads();
      first.get(1, TimeUnit.SECONDS);
      second.get(1, TimeUnit.SECONDS);

      assertThat(bothReadsStarted).isTrue();
    }
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

  private static final class BlockingUserAccountRepository extends FakeUserAccountRepository {

    private final CountDownLatch readsStarted = new CountDownLatch(2);
    private final CountDownLatch readsReleased = new CountDownLatch(1);

    @Override
    public Optional<UserAccount> findById(UUID accountId) {
      readsStarted.countDown();
      try {
        if (!readsReleased.await(2, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Concurrent account reads did not arrive");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Concurrent account read was interrupted", exception);
      }
      return super.findById(accountId);
    }

    boolean awaitBothReads() throws InterruptedException {
      return readsStarted.await(1, TimeUnit.SECONDS);
    }

    void releaseReads() {
      readsReleased.countDown();
    }
  }
}
