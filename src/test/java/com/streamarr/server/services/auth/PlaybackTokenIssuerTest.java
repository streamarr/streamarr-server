package com.streamarr.server.services.auth;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static com.streamarr.server.support.TokenTestSupport.tokenProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.support.TokenTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

@Tag("UnitTest")
@DisplayName("Playback Token Issuer Tests")
class PlaybackTokenIssuerTest {

  private final AuthTokenProperties properties = tokenProperties();

  private final TokenCryptoConfig cryptoConfig = new TokenCryptoConfig();
  private final FakePlaybackAuthorityGate authorityGate = new FakePlaybackAuthorityGate();

  private final PlaybackTokenIssuer issuer =
      new PlaybackTokenIssuer(
          cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(properties)),
          properties,
          Clock.systemUTC(),
          authorityGate);

  private final UUID accountId = UUID.randomUUID();
  private final UUID sessionId = UUID.randomUUID();
  private final UUID householdId = UUID.randomUUID();
  private final UUID profileId = UUID.randomUUID();

  @Test
  @DisplayName("Should bind identity and stream session when issuing")
  void shouldBindIdentityAndStreamSessionWhenIssuing() {
    var streamSession = sessionOwnedBy(profileId);
    var streamSessionId = streamSession.getSessionId();

    var token = issuer.issue(profileIdentity(), authority(), streamSession, Duration.ofHours(24));

    assertThat(token.scope()).isEqualTo(TokenScope.PLAYBACK);
    var decoded = decode(token.value());
    assertThat(decoded.getClaimAsString(JwtClaimNames.ISS)).isEqualTo("streamarr");
    assertThat(decoded.getAudience()).containsExactly("streamarr");
    assertThat(decoded.getSubject()).isEqualTo(accountId.toString());
    assertThat(decoded.getClaimAsString(TokenClaims.SESSION_ID)).isEqualTo(sessionId.toString());
    assertThat(decoded.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("playback");
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ID))
        .isEqualTo(householdId.toString());
    assertThat(decoded.getClaimAsString(TokenClaims.PROFILE_ID)).isEqualTo(profileId.toString());
    assertThat(decoded.getClaimAsString("stream_session_id")).isEqualTo(streamSessionId.toString());
    assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
        .isEqualTo(Duration.ofHours(24));
  }

  @Test
  @DisplayName("Should reject issuance when identity has no profile")
  void shouldRejectIssuanceWhenIdentityHasNoProfile() {
    var accountScoped =
        AuthenticatedIdentity.builder()
            .accountId(accountId)
            .role(AccountRole.USER)
            .authSessionId(sessionId)
            .scope(TokenScope.ACCOUNT)
            .build();
    var streamSession = defaultSessionBuilder().build();
    var ttl = Duration.ofHours(1);

    assertThatThrownBy(() -> issuer.issue(accountScoped, streamSession, ttl))
        .isInstanceOf(ProfileRequiredException.class);
  }

  @Test
  @DisplayName("Should refuse issuance when session not owned by identity")
  void shouldRefuseIssuanceWhenSessionNotOwnedByIdentity() {
    var identity = profileIdentity();
    var foreignSession = sessionOwnedBy(UUID.randomUUID());
    var ttl = Duration.ofHours(1);

    // The issuer is the only authority that mints playback capability: whatever future caller
    // asks, an unowned session must never become a token, and reads as missing.
    assertThatThrownBy(() -> issuer.issue(identity, authority(), foreignSession, ttl))
        .isInstanceOf(SessionNotFoundException.class);
  }

  @Test
  @DisplayName("Should refuse issuance when playback authority is no longer live")
  void shouldRefuseIssuanceWhenPlaybackAuthorityIsNoLongerLive() {
    var identity = profileIdentity();
    var streamSession = sessionOwnedBy(profileId);
    var ttl = Duration.ofHours(1);
    authorityGate.deny();

    assertThatThrownBy(() -> issuer.issue(identity, authority(), streamSession, ttl))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  private AuthenticatedIdentity profileIdentity() {
    return AuthenticatedIdentity.builder()
        .accountId(accountId)
        .role(AccountRole.USER)
        .authSessionId(sessionId)
        .scope(TokenScope.PROFILE)
        .profileId(profileId)
        .build();
  }

  private PlaybackAuthority authority() {
    return PlaybackAuthority.builder()
        .authSessionId(sessionId)
        .accountId(accountId)
        .householdId(householdId)
        .profileId(profileId)
        .build();
  }

  private StreamSession sessionOwnedBy(UUID ownerProfileId) {
    return defaultSessionBuilder().authority(playbackAuthorityFor(ownerProfileId)).build();
  }

  private org.springframework.security.oauth2.jwt.Jwt decode(String token) {
    return TokenTestSupport.decode(token, properties);
  }
}
