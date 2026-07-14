package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Playback-URL tokens for playlist-driven players that cannot attach headers to segment requests.
 * Signed with the same key and validated by the same decoder as API tokens, carrying the identity
 * and stream-session binding. Scope is playback: outside the hierarchy, these tokens authorize
 * nothing but stream paths. Validity covers one media traversal plus the configured retention
 * window for pause and slow-playback slack; live authority is checked on every request.
 */
@Service
@RequiredArgsConstructor
public class PlaybackTokenIssuer {

  private final JwtEncoder jwtEncoder;
  private final AuthTokenProperties properties;
  private final Clock clock;
  private final PlaybackAuthorityGate authorityGate;

  public AccessToken issue(
      AuthenticatedIdentity identity, StreamSession streamSession, Duration validity) {
    if (identity.profileId() == null || identity.householdId() == null) {
      throw new ProfileRequiredException();
    }
    if (!authorityGate.allows(identity.playbackAuthority())) {
      throw new AuthenticationRequiredException();
    }

    // This is the only place playback capability is minted, so ownership is enforced here
    // rather than trusted to callers: an unowned session must never become a playable token,
    // and reads as missing (no existence oracle).
    if (!streamSession.isOwnedBy(identity.profileId())) {
      throw new SessionNotFoundException(streamSession.getSessionId());
    }

    var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    var expiresAt = now.plus(validity);

    var claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .id(UUID.randomUUID().toString())
            .subject(identity.accountId().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(TokenClaims.ROLES, List.of(identity.role().name()))
            .claim(TokenClaims.SESSION_ID, identity.authSessionId().toString())
            .claim(TokenClaims.SCOPE, TokenScope.PLAYBACK.claimValue())
            .claim(TokenClaims.HOUSEHOLD_ID, identity.householdId().toString())
            .claim(TokenClaims.HOUSEHOLD_ROLE, identity.householdRole().name())
            .claim(TokenClaims.PROFILE_ID, identity.profileId().toString())
            .claim(TokenClaims.STREAM_SESSION_ID, streamSession.getSessionId().toString())
            .build();

    var jwt =
        jwtEncoder.encode(
            JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.ES256).build(), claims));

    return AccessToken.builder()
        .value(jwt.getTokenValue())
        .expiresAt(expiresAt)
        .scope(TokenScope.PLAYBACK)
        .build();
  }
}
