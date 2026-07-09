package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.RefreshToken;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  private static final int RAW_TOKEN_BYTES = 32;

  private final AuthSessionRepository sessionRepository;
  private final RefreshTokenRepository tokenRepository;
  private final AuthTokenProperties properties;
  private final Clock clock;
  private final TokenReuseRevoker tokenReuseRevoker;

  private final SecureRandom secureRandom = new SecureRandom();

  @Transactional
  public IssuedRefreshToken createSession(UserAccount account, String deviceName) {
    var session =
        sessionRepository.save(
            AuthSession.builder().accountId(account.getId()).deviceName(deviceName).build());

    var rawToken = generateRawToken();
    tokenRepository.save(buildActiveToken(session, rawToken, clock.instant()));

    return new IssuedRefreshToken(rawToken, session);
  }

  // Reuse detection must survive its own exception: the family revocation and version bump
  // committed here are the security response, and the throw is only the caller's signal.
  @Transactional(noRollbackFor = TokenReuseDetectedException.class)
  public RefreshResult redeem(String rawToken) {
    var digest = digestOf(rawToken);
    var now = clock.instant();

    var sessionId =
        tokenRepository
            .findSessionIdByDigest(digest)
            .orElseThrow(InvalidRefreshTokenException::new);

    // Serialize with revocation on the session row before touching tokens (same lock order as
    // revoke), so a successor can never be inserted onto a session that is being revoked.
    var session =
        sessionRepository.lockById(sessionId).orElseThrow(InvalidRefreshTokenException::new);

    if (session.getRevokedAt() != null) {
      // Redeeming any token of a revoked session — including a successor that raced revocation —
      // is reuse: never rotate, never mint.
      throw new TokenReuseDetectedException();
    }

    // Rotation is a single conditional statement: exactly one caller can consume an ACTIVE token.
    if (tokenRepository.consumeActiveToken(digest, now) > 0) {
      return rotate(session, now);
    }

    return handleUnconsumedToken(digest, session, now);
  }

  private RefreshResult rotate(AuthSession session, Instant now) {
    var rawToken = generateRawToken();
    tokenRepository.save(buildActiveToken(session, rawToken, now));

    return new RefreshResult.Rotated(rawToken, session);
  }

  private RefreshResult handleUnconsumedToken(String digest, AuthSession session, Instant now) {
    var token = tokenRepository.findByDigest(digest).orElseThrow(InvalidRefreshTokenException::new);

    // The session is not revoked (guarded above), so grace turns purely on token timing.
    if (isWithinGrace(token, now)) {
      return new RefreshResult.GraceReplay(session);
    }

    if (token.getStatus() == RefreshTokenStatus.ACTIVE) {
      // Active but past expiry — stale, not theft.
      throw new InvalidRefreshTokenException();
    }

    revokeSessionForReuse(session, now);
    throw new TokenReuseDetectedException();
  }

  private boolean isWithinGrace(RefreshToken token, Instant now) {
    return token.getStatus() == RefreshTokenStatus.ROTATED
        && token.getRotatedAt() != null
        && !now.isAfter(token.getRotatedAt().plus(properties.rotationGrace()));
  }

  private void revokeSessionForReuse(AuthSession session, Instant now) {
    log.warn(
        "Refresh token reuse detected — revoking session {} for account {}",
        session.getId(),
        session.getAccountId());

    tokenReuseRevoker.revokeAfterCompletion(session.getId(), now);
  }

  private RefreshToken buildActiveToken(AuthSession session, String rawToken, Instant now) {
    return RefreshToken.builder()
        .sessionId(session.getId())
        .digest(digestOf(rawToken))
        .status(RefreshTokenStatus.ACTIVE)
        .expiresAt(now.plus(properties.refreshTokenTtl()))
        .build();
  }

  private String generateRawToken() {
    var bytes = new byte[RAW_TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String digestOf(String rawToken) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required but unavailable.", e);
    }
  }
}
