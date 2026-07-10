package com.streamarr.server.services.auth;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
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
 * Signed with the same key and validated by the same decoder as API tokens, carrying the full
 * identity and version claims — password, membership, and policy changes all kill playback — plus
 * the stream-session binding. Scope is playback: outside the hierarchy, these tokens authorize
 * nothing but stream paths. Validity covers one media traversal plus the configured retention
 * window for pause and slow-playback slack; revocation stays instant through the per-request
 * version check.
 */
@Service
@RequiredArgsConstructor
public class PlaybackTokenIssuer {

  private final JwtEncoder jwtEncoder;
  private final Clock clock;
  private final TokenVersionCache versionCache;

  public AccessToken issue(
      AuthenticatedIdentity identity, StreamSession streamSession, Duration validity) {
    if (identity.profileId() == null || identity.householdId() == null) {
      throw new ProfileRequiredException();
    }

    // This is the only place playback capability is minted, so ownership is enforced here
    // rather than trusted to callers: an unowned session must never become a playable token,
    // and reads as missing (no existence oracle).
    if (!streamSession.isOwnedBy(identity.profileId())) {
      throw new SessionNotFoundException(streamSession.getSessionId());
    }

    // A derived credential cannot acquire authority newer than the source request proved.
    var sessionVersion = requireCurrentSessionVersion(identity);
    var membershipVersion = requireCurrentMembershipVersion(identity);
    var policyVersion = requireCurrentPolicyVersion(identity);

    var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    var expiresAt = now.plus(validity);

    var claims =
        JwtClaimsSet.builder()
            .issuer(TokenContract.ISSUER)
            .id(UUID.randomUUID().toString())
            .subject(identity.accountId().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(TokenClaims.ROLE, identity.role().name())
            .claim(TokenClaims.SESSION_ID, identity.sessionId().toString())
            .claim(TokenClaims.SESSION_VERSION, sessionVersion)
            .claim(TokenClaims.SCOPE, TokenScope.PLAYBACK.claimValue())
            .claim(TokenClaims.HOUSEHOLD_ID, identity.householdId().toString())
            .claim(TokenClaims.HOUSEHOLD_ROLE, identity.householdRole().name())
            .claim(TokenClaims.MEMBERSHIP_VERSION, membershipVersion)
            .claim(TokenClaims.PROFILE_ID, identity.profileId().toString())
            .claim(TokenClaims.POLICY_VERSION, policyVersion)
            .claim(TokenClaims.STREAM_SESSION, streamSession.getSessionId().toString())
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

  private long requireCurrentSessionVersion(AuthenticatedIdentity identity) {
    var sourceVersion = identity.sessionVersion();
    if (sourceVersion == null) {
      throw new AuthenticationRequiredException();
    }

    return versionCache
        .sessionVersion(identity.sessionId())
        .filter(sourceVersion::equals)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  private long requireCurrentMembershipVersion(AuthenticatedIdentity identity) {
    var sourceVersion = identity.membershipVersion();
    if (sourceVersion == null) {
      throw new ProfileRequiredException();
    }

    return versionCache
        .membershipVersion(identity.accountId(), identity.householdId())
        .filter(sourceVersion::equals)
        .orElseThrow(ProfileRequiredException::new);
  }

  private long requireCurrentPolicyVersion(AuthenticatedIdentity identity) {
    var sourceVersion = identity.policyVersion();
    if (sourceVersion == null) {
      throw new ProfileRequiredException();
    }

    return versionCache
        .profilePolicyVersion(identity.profileId())
        .filter(sourceVersion::equals)
        .orElseThrow(ProfileRequiredException::new);
  }
}
