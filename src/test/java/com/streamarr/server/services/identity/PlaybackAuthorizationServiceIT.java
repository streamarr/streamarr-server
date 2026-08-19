package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR 0018's live playback gate, decided by Cedar against real PostgreSQL: the next playback
 * request is refused after logout, Account disablement, unsharing, or loss of Household access, and
 * any mismatch between the stream's authority and the request's identity reads as denied.
 */
@Tag("IntegrationTest")
@DisplayName("Playback Authorization Service Integration Tests")
@Import(AuthTestSupportConfig.class)
class PlaybackAuthorizationServiceIT extends AbstractIntegrationTest {

  @Autowired private PlaybackAuthorityGate authorityGate;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private TransactionTemplate transactionTemplate;

  private AuthTestSupport.TestIdentity identity;
  private AuthTestSupport.TestIdentity host;
  private PlaybackAuthority authority;

  @BeforeEach
  void setUp() {
    identity = authTestSupport.createIdentity();
    authenticateAs(identity);
    authority = currentIdentity().playbackAuthority();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    if (host != null) {
      authTestSupport.deleteIdentity(host);
    }
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should allow playback while session, Account, Household access, and share are live")
  void shouldAllowPlaybackWhileSessionAccountHouseholdAccessAndShareAreLive() {
    assertThat(authorityGate.allows(authority)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(SessionRevocationReason.class)
  @DisplayName("Should deny playback for every session revocation reason")
  void shouldDenyPlaybackForEverySessionRevocationReason(SessionRevocationReason reason) {
    authSessionRepository.revoke(identity.session().getId(), reason, Instant.now());

    assertThat(authorityGate.allows(authority)).isFalse();
  }

  @Test
  @DisplayName("Should deny playback when the Account is disabled")
  void shouldDenyPlaybackWhenAccountIsDisabled() {
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    assertThat(authorityGate.allows(authority)).isFalse();
  }

  @Test
  @DisplayName(
      "Should deny playback when the selected Profile is unshared from the context Household")
  void shouldDenyPlaybackWhenSelectedProfileIsUnsharedFromContextHousehold() {
    host = authTestSupport.createIdentity();
    // An unlinked Profile the host manages, available in the host's Household — created in one
    // transaction because the deferred home-anchor trigger checks the whole shape at commit.
    var managedShare =
        transactionTemplate.execute(
            _ -> {
              var managed =
                  profileRepository.saveAndFlush(
                      ProfileFixture.defaultProfileBuilder()
                          .householdId(host.household().getId())
                          .build());
              profileManagerRepository.saveAndFlush(
                  ProfileManager.builder()
                      .accountId(host.account().getId())
                      .profileId(managed.getId())
                      .build());
              return share(managed.getId(), host.household().getId());
            });
    var managed = profileRepository.findById(managedShare.getProfileId()).orElseThrow();
    // ... watched by a visitor whose own Personal Profile share grants Household access.
    share(identity.profile().getId(), host.household().getId());
    authenticateAs(identity, host.household().getId(), managed.getId());
    var visitingAuthority = currentIdentity().playbackAuthority();
    assertThat(authorityGate.allows(visitingAuthority)).isTrue();

    end(managedShare);

    assertThat(authorityGate.allows(visitingAuthority)).isFalse();
  }

  @Test
  @DisplayName("Should deny playback when the Account may no longer use the context Household")
  void shouldDenyPlaybackWhenAccountMayNoLongerUseContextHousehold() {
    host = authTestSupport.createIdentity();
    var visit = share(identity.profile().getId(), host.household().getId());
    // The visitor watches as the host's Personal Profile (available there) — access to the
    // Household itself rests only on the visitor's own share.
    authenticateAs(identity, host.household().getId(), host.profile().getId());
    var visitingAuthority = currentIdentity().playbackAuthority();
    assertThat(authorityGate.allows(visitingAuthority)).isTrue();

    end(visit);

    assertThat(authorityGate.allows(visitingAuthority)).isFalse();
  }

  @Test
  @DisplayName(
      "Should deny playback when the stream's authority does not match the request identity")
  void shouldDenyPlaybackWhenStreamAuthorityDoesNotMatchRequestIdentity() {
    assertThat(authorityGate.allows(copy().authSessionId(UUID.randomUUID()).build())).isFalse();
    assertThat(authorityGate.allows(copy().accountId(UUID.randomUUID()).build())).isFalse();
    assertThat(authorityGate.allows(copy().householdId(UUID.randomUUID()).build())).isFalse();
    assertThat(authorityGate.allows(copy().profileId(UUID.randomUUID()).build())).isFalse();
  }

  @Test
  @DisplayName("Should deny playback when the request carries no selected Profile")
  void shouldDenyPlaybackWhenRequestCarriesNoSelectedProfile() {
    var accountScoped =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(authTestSupport.accountBearer(identity)));
    authenticate(accountScoped);

    assertThat(authorityGate.allows(authority)).isFalse();
  }

  @Test
  @DisplayName("Should not deadlock when a share ends concurrently with a playback request")
  void shouldNotDeadlockWhenShareEndsConcurrentlyWithPlaybackRequest() throws Exception {
    host = authTestSupport.createIdentity();
    var visit = share(identity.profile().getId(), host.household().getId());
    authenticateAs(identity, host.household().getId(), identity.profile().getId());
    var visitingAuthority = currentIdentity().playbackAuthority();
    var requestIdentity = currentIdentity();
    var start = new CyclicBarrier(2);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var access =
          executor.submit(
              () ->
                  awaitThen(
                      start,
                      () -> {
                        authenticate(requestIdentity);
                        return authorityGate.allows(visitingAuthority);
                      }));
      var unshare = executor.submit(() -> awaitThen(start, () -> end(visit)));

      assertThat(access.get(10, TimeUnit.SECONDS)).isIn(true, false);
      assertThat(unshare.get(10, TimeUnit.SECONDS)).isNotNull();
    }

    assertThat(authorityGate.allows(visitingAuthority)).isFalse();
  }

  private <T> T awaitThen(CyclicBarrier barrier, Callable<T> action) throws Exception {
    barrier.await(10, TimeUnit.SECONDS);
    return action.call();
  }

  private ProfileHouseholdShare share(UUID profileId, UUID householdId) {
    return shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
  }

  private ProfileHouseholdShare end(ProfileHouseholdShare share) {
    var ending = shareRepository.findById(share.getId()).orElseThrow();
    ending.setStatus(ProfileShareStatus.ENDED);
    ending.setEndedAt(Instant.now());
    return shareRepository.saveAndFlush(ending);
  }

  private void authenticateAs(AuthTestSupport.TestIdentity source) {
    authenticate(
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(authTestSupport.profileBearer(source))));
  }

  /** A session of {@code source} using another Household with a selected Profile there. */
  private void authenticateAs(
      AuthTestSupport.TestIdentity source, UUID contextHouseholdId, UUID profileId) {
    var base =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(authTestSupport.profileBearer(source)));
    authenticate(
        AuthenticatedIdentity.builder()
            .accountId(base.accountId())
            .authSessionId(base.authSessionId())
            .scope(TokenScope.PROFILE)
            .householdId(base.householdId())
            .householdRole(base.householdRole())
            .serverAdmin(base.serverAdmin())
            .contextHouseholdId(contextHouseholdId)
            .profileId(profileId)
            .build());
  }

  private void authenticate(AuthenticatedIdentity identity) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new StreamarrAuthenticationToken(
                identity, null, List.of(new SimpleGrantedAuthority(identity.scope().authority()))));
  }

  private AuthenticatedIdentity currentIdentity() {
    return ((StreamarrAuthenticationToken) SecurityContextHolder.getContext().getAuthentication())
        .getPrincipal();
  }

  private PlaybackAuthority.PlaybackAuthorityBuilder copy() {
    return PlaybackAuthority.builder()
        .authSessionId(authority.authSessionId())
        .accountId(authority.accountId())
        .householdId(authority.householdId())
        .profileId(authority.profileId());
  }
}
