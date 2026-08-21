package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Playback Authorization Service Tests")
class PlaybackAuthorizationServiceTest {

  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.profileScopedBuilder().build());
  private final PlaybackAuthorizationService service =
      new PlaybackAuthorizationService(authorization);

  @Test
  @DisplayName("Should allow playback when the authority matches and Cedar allows")
  void shouldAllowPlaybackWhenAuthorityMatchesAndCedarAllows() {
    var authority = authorization.currentIdentity().playbackAuthority();

    assertThat(service.allows(authority)).isTrue();
    assertThat(authorization.recordedIntents()).containsExactly(new Intent.Playback());
  }

  @Test
  @DisplayName("Should deny playback when the stream's authority is not this request's identity")
  void shouldDenyPlaybackWhenStreamAuthorityIsNotThisRequestsIdentity() {
    var own = authorization.currentIdentity().playbackAuthority();
    var other =
        PlaybackAuthority.builder()
            .authSessionId(own.authSessionId())
            .accountId(own.accountId())
            .householdId(own.householdId())
            .profileId(UUID.randomUUID())
            .build();

    assertThat(service.allows(other)).isFalse();
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should deny playback when the request identity has no selected Profile")
  void shouldDenyPlaybackWhenRequestIdentityHasNoSelectedProfile() {
    var accountAuthorization =
        new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());
    var accountScoped = new PlaybackAuthorizationService(accountAuthorization);
    var authority = authorization.currentIdentity().playbackAuthority();

    assertThat(accountScoped.allows(authority)).isFalse();
    assertThat(accountAuthorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should deny playback when Cedar denies or cannot decide")
  void shouldDenyPlaybackWhenCedarDeniesOrCannotDecide() {
    var authority = authorization.currentIdentity().playbackAuthority();

    authorization.denyAll();
    assertThat(service.allows(authority)).isFalse();
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    assertThat(service.allows(authority)).isFalse();
  }
}
