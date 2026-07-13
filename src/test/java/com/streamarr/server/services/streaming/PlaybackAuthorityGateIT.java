package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.UUID;
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
  @Autowired private HouseholdMembershipRepository membershipRepository;
  @Autowired private AccountProfileRepository accountProfileRepository;

  private AuthTestSupport.TestIdentity identity;
  private PlaybackAuthority authority;

  @BeforeEach
  void setUp() {
    identity = authTestSupport.createIdentity();
    identity.session().setActiveHouseholdId(identity.household().getId());
    identity.session().setActiveProfileId(identity.profile().getId());
    authSessionRepository.updateSelectionIfLive(identity.session(), Instant.now());
    authority = authorityFor(identity);
  }

  @AfterEach
  void tearDown() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should allow current playback authority")
  void shouldAllowCurrentPlaybackAuthority() {
    assertThat(authorityGate.allows(authority)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(SessionRevocationReason.class)
  @DisplayName("Should deny playback authority for every authorization session revocation reason")
  void shouldDenyPlaybackAuthorityForEveryAuthorizationSessionRevocationReason(
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
  @DisplayName("Should deny playback authority when household membership is revoked")
  void shouldDenyPlaybackAuthorityWhenHouseholdMembershipIsRevoked() {
    membershipRepository.revokeMembership(identity.account().getId(), identity.household().getId());

    assertThat(authorityGate.allows(authority)).isFalse();
  }

  @Test
  @DisplayName("Should deny playback authority when profile grant is revoked")
  void shouldDenyPlaybackAuthorityWhenProfileGrantIsRevoked() {
    accountProfileRepository.revokeProfileLink(
        AccountProfile.builder()
            .accountId(identity.account().getId())
            .householdId(identity.household().getId())
            .profileId(identity.profile().getId())
            .build());

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
  @DisplayName("Should not deadlock with concurrent profile grant revocation on PostgreSQL 18")
  void shouldNotDeadlockWithConcurrentProfileGrantRevocationOnPostgresql18() throws Exception {
    var start = new CyclicBarrier(2);
    var link =
        AccountProfile.builder()
            .accountId(identity.account().getId())
            .householdId(identity.household().getId())
            .profileId(identity.profile().getId())
            .build();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var access = executor.submit(() -> awaitThen(start, () -> authorityGate.allows(authority)));
      var revoke =
          executor.submit(
              () -> awaitThen(start, () -> accountProfileRepository.revokeProfileLink(link)));

      assertThat(access.get(5, TimeUnit.SECONDS)).isIn(true, false);
      assertThat(revoke.get(5, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(authorityGate.allows(authority)).isFalse();
  }

  private <T> T awaitThen(CyclicBarrier barrier, java.util.concurrent.Callable<T> action)
      throws Exception {
    barrier.await();
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
