package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
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

@Tag("IntegrationTest")
@DisplayName("Playback Authority Gate Integration Tests")
class PlaybackAuthorityGateIT extends AbstractIntegrationTest {

  @Autowired private PlaybackAuthorityGate authorityGate;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileHouseholdShareRepository profileShareRepository;

  private AuthTestSupport.TestIdentity identity;
  private PlaybackAuthority authority;

  @BeforeEach
  void setUp() {
    identity = authTestSupport.createIdentity();
    identity.session().setActiveProfileId(identity.profile().getId());
    authSessionRepository.updateSelectionIfLive(identity.session(), Instant.now());
    authority = authorityFor(identity);
  }

  @AfterEach
  void tearDown() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName(
      "Should deny prior playback authority after the session switches active profile when authorizing playback")
  void shouldDenyPriorPlaybackAuthorityAfterSessionSwitchesActiveProfileWhenAuthorizingPlayback() {
    var alternate = authTestSupport.createIdentity();
    try {
      assertThat(authorityGate.allows(authority)).isTrue();

      identity.session().setActiveProfileId(alternate.profile().getId());

      assertThat(authSessionRepository.updateSelectionIfLive(identity.session(), Instant.now()))
          .isTrue();
      assertThat(authorityGate.allows(authority)).isFalse();
    } finally {
      authTestSupport.deleteIdentity(alternate);
    }
  }

  @ParameterizedTest
  @EnumSource(SessionRevocationReason.class)
  @DisplayName(
      "Should deny playback authority for every authorization session revocation reason when authorizing playback")
  void
      shouldDenyPlaybackAuthorityForEveryAuthorizationSessionRevocationReasonWhenAuthorizingPlayback(
          SessionRevocationReason reason) {
    authSessionRepository.revoke(identity.session().getId(), reason, Instant.now());

    assertThat(authorityGate.allows(authority)).isFalse();
  }

  @Test
  @DisplayName("Should deny playback authority when account is disabled")
  void shouldDenyPlaybackAuthorityWhenAccountIsDisabled() {
    identity.account().setEnabled(false);
    userAccountRepository.saveAndFlush(identity.account());

    assertThat(authorityGate.allows(authority)).isFalse();
  }

  @Test
  @DisplayName("Should deny playback authority when active profile share is removed")
  void shouldDenyPlaybackAuthorityWhenActiveProfileShareRemoved() {
    profileShareRepository.delete(
        profileShareRepository
            .findByProfileIdAndHouseholdId(identity.profile().getId(), identity.household().getId())
            .orElseThrow());

    assertThat(authorityGate.allows(authority)).isFalse();
  }

  @Test
  @DisplayName("Should deny playback authority when any identity component mismatches")
  void shouldDenyPlaybackAuthorityWhenAnyIdentityComponentMismatches() {
    assertThat(authorityGate.allows(authorityWithAuthSessionId(UUID.randomUUID()))).isFalse();
    assertThat(authorityGate.allows(authorityWithAccountId(UUID.randomUUID()))).isFalse();
    assertThat(authorityGate.allows(authorityWithHouseholdId(UUID.randomUUID()))).isFalse();
    assertThat(authorityGate.allows(authorityWithProfileId(UUID.randomUUID()))).isFalse();
  }

  @Test
  @DisplayName(
      "Should not deadlock with concurrent profile share removal on PostgreSQL 18 when authorizing playback")
  void shouldNotDeadlockWithConcurrentProfileShareRemovalOnPostgresql18WhenAuthorizingPlayback()
      throws Exception {
    var start = new CyclicBarrier(2);
    var share =
        profileShareRepository
            .findByProfileIdAndHouseholdId(identity.profile().getId(), identity.household().getId())
            .orElseThrow();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var access = executor.submit(() -> awaitThen(start, () -> authorityGate.allows(authority)));
      var revoke =
          executor.submit(
              () ->
                  awaitThen(
                      start,
                      () -> {
                        profileShareRepository.delete(share);
                        return true;
                      }));

      assertThat(access.get(5, TimeUnit.SECONDS)).isIn(true, false);
      assertThat(revoke.get(5, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(authorityGate.allows(authority)).isFalse();
  }

  private <T> T awaitThen(CyclicBarrier barrier, Callable<T> action) throws Exception {
    barrier.await(5, TimeUnit.SECONDS);
    return action.call();
  }

  private PlaybackAuthority authorityFor(AuthTestSupport.TestIdentity source) {
    return PlaybackAuthority.builder()
        .authSessionId(source.session().getId())
        .accountId(source.account().getId())
        .householdId(source.household().getId())
        .profileId(source.profile().getId())
        .build();
  }

  private PlaybackAuthority authorityWithAuthSessionId(UUID authSessionId) {
    return copyAuthority().authSessionId(authSessionId).build();
  }

  private PlaybackAuthority authorityWithAccountId(UUID accountId) {
    return copyAuthority().accountId(accountId).build();
  }

  private PlaybackAuthority authorityWithHouseholdId(UUID householdId) {
    return copyAuthority().householdId(householdId).build();
  }

  private PlaybackAuthority authorityWithProfileId(UUID profileId) {
    return copyAuthority().profileId(profileId).build();
  }

  private PlaybackAuthority.PlaybackAuthorityBuilder copyAuthority() {
    return PlaybackAuthority.builder()
        .authSessionId(authority.authSessionId())
        .accountId(authority.accountId())
        .householdId(authority.householdId())
        .profileId(authority.profileId());
  }
}
