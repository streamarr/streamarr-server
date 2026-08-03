package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.RefreshToken;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidRefreshProposalException;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  private static final int RAW_TOKEN_BYTES = 32;
  private static final String SUCCESSOR_HMAC_ALGORITHM = "HmacSHA256";
  private static final String SUCCESSOR_DOMAIN = "streamarr.refresh-token.successor.v1:";

  private final AuthSessionRepository sessionRepository;
  private final RefreshTokenRepository tokenRepository;
  private final AuthTokenProperties properties;
  private final Clock clock;
  private final TokenReuseRevoker tokenReuseRevoker;

  private final SecureRandom secureRandom = new SecureRandom();

  @Transactional
  public IssuedRefreshToken createSession(UserAccount account, String deviceName) {
    return createSessionAndToken(
        CreateAuthSessionCommand.builder()
            .accountId(account.getId())
            .deviceName(deviceName)
            .build());
  }

  @Transactional
  public IssuedRefreshToken createSession(CreateAuthSessionCommand command) {
    return createSessionAndToken(command);
  }

  private IssuedRefreshToken createSessionAndToken(CreateAuthSessionCommand command) {
    var session =
        sessionRepository.save(
            AuthSession.builder()
                .accountId(command.accountId())
                .deviceName(command.deviceName())
                .activeHouseholdId(command.activeHouseholdId())
                .activeProfileId(command.activeProfileId())
                .build());

    var rawToken = generateRawToken();
    tokenRepository.save(buildActiveToken(session, rawToken, clock.instant()));

    return new IssuedRefreshToken(rawToken, session);
  }

  @Transactional
  public void logout(java.util.UUID sessionId) {
    var now = clock.instant();
    sessionRepository.revoke(sessionId, SessionRevocationReason.LOGOUT, now);
    tokenRepository.revokeAllForSession(sessionId, now);
  }

  // The family revocation must outlive this method's rollback: TokenReuseRevoker applies it in
  // an after-completion REQUIRES_NEW transaction; the thrown exception is only the caller's
  // signal.
  @Transactional(noRollbackFor = TokenReuseDetectedException.class)
  public RefreshResult redeem(RefreshCommand command) {
    var rawToken = command.refreshToken();
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
      logTokenReuse(session);
      throw new TokenReuseDetectedException();
    }

    // Rotation is a single conditional statement: exactly one caller can consume an ACTIVE token.
    if (tokenRepository.consumeActiveToken(digest, now) > 0) {
      return rotate(command, session, now);
    }

    return handleUnconsumedToken(command, session, now);
  }

  private RefreshResult rotate(RefreshCommand command, AuthSession session, Instant now) {
    var predecessor =
        tokenRepository
            .findByDigest(digestOf(command.refreshToken()))
            .orElseThrow(InvalidRefreshTokenException::new);

    if (command.carriesProposal()) {
      return rotateToProposal(command.proposedSuccessor(), predecessor, session, now);
    }

    var rawSuccessor = deriveSuccessor(command.refreshToken(), predecessor.getId());
    tokenRepository.save(buildActiveToken(session, rawSuccessor, now));

    return new RefreshResult.Rotated(rawSuccessor, session);
  }

  /**
   * Records the client's own successor and the lineage link that later proves the pair. The
   * proposal is stored only as a digest, exactly like a server-generated successor.
   */
  private RefreshResult rotateToProposal(
      String proposal, RefreshToken predecessor, AuthSession session, Instant now) {
    var successor = buildActiveToken(session, proposal, now);
    successor.setPredecessorId(predecessor.getId());

    try {
      tokenRepository.saveAndFlush(successor);
    } catch (DataIntegrityViolationException _) {
      // The proposal collides with an existing digest or lineage row. Rolling the whole
      // transaction back un-consumes the presented token, so the client can retry with a fresh
      // proposal rather than losing its session to a bad guess.
      throw new InvalidRefreshProposalException();
    }

    return new RefreshResult.Rotated(proposal, session);
  }

  private RefreshResult handleUnconsumedToken(
      RefreshCommand command, AuthSession session, Instant now) {
    var token =
        tokenRepository
            .findByDigest(digestOf(command.refreshToken()))
            .orElseThrow(InvalidRefreshTokenException::new);

    if (command.carriesProposal()) {
      return recoverProposedSuccessor(command.proposedSuccessor(), token, session, now);
    }

    return retryDerivedSuccessor(command.refreshToken(), token, session, now);
  }

  /**
   * A proposal replaces the grace window entirely: possession of the successor already carries the
   * authority grace was standing in for, so recovery works however long ago the rotation committed
   * — and a proposal that was never this token's successor is theft at any timing.
   */
  private RefreshResult recoverProposedSuccessor(
      String proposal, RefreshToken predecessor, AuthSession session, Instant now) {
    if (predecessor.getStatus() != RefreshTokenStatus.ROTATED) {
      // An expired ACTIVE token is stale. A REVOKED token may have lost a race to intentional
      // family reissue. Only a consumed token can be reuse.
      throw new InvalidRefreshTokenException();
    }

    var proposalDigest = digestOf(proposal);
    var isProposalTheRecordedSuccessor =
        tokenRepository
            .findSuccessorDigest(predecessor.getId())
            .filter(proposalDigest::equals)
            .isPresent();

    if (!isProposalTheRecordedSuccessor) {
      // A consumed token presented with a successor it was never promised: only the legitimate
      // client could know the real one.
      revokeSessionForReuse(session, now);
      throw new TokenReuseDetectedException();
    }

    if (tokenRepository.isActiveToken(session.getId(), proposalDigest, now)) {
      return new RefreshResult.Recovered(proposal, session);
    }

    // The exact pair, but its successor has itself moved on. The client is behind, not hostile —
    // so the family survives — and it learns nothing beyond the terminal answer every other
    // unusable token gets.
    log.info(
        "Refresh pair is exact but superseded for session {}; no revocation.", session.getId());
    throw new InvalidRefreshTokenException();
  }

  private RefreshResult retryDerivedSuccessor(
      String rawToken, RefreshToken token, AuthSession session, Instant now) {
    // The session is not revoked (guarded above), so grace turns purely on token timing.
    if (isWithinGrace(token, now)) {
      var rawSuccessor = deriveSuccessor(rawToken, token.getId());
      if (tokenRepository.isActiveToken(session.getId(), digestOf(rawSuccessor), now)) {
        return new RefreshResult.GraceRetry(rawSuccessor, session);
      }
      return new RefreshResult.SupersededRetry(session);
    }

    if (token.getStatus() != RefreshTokenStatus.ROTATED) {
      // An expired ACTIVE token is stale. A REVOKED token may have lost a race to intentional
      // family reissue. Only a past-grace ROTATED token proves reuse.
      throw new InvalidRefreshTokenException();
    }

    revokeSessionForReuse(session, now);
    throw new TokenReuseDetectedException();
  }

  private boolean isWithinGrace(RefreshToken token, Instant now) {
    // A ROTATED row missing its timestamp is anomalous state no writer produces; refuse grace so
    // it lands on the theft path rather than an error.
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

  private static void logTokenReuse(AuthSession session) {
    log.warn("Refresh token reuse detected for sessionId={}", session.getId());
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

  private static String deriveSuccessor(String rawPredecessor, UUID predecessorId) {
    try {
      var key = Base64.getUrlDecoder().decode(rawPredecessor);
      var mac = Mac.getInstance(SUCCESSOR_HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(key, SUCCESSOR_HMAC_ALGORITHM));
      var message = (SUCCESSOR_DOMAIN + predecessorId).getBytes(StandardCharsets.UTF_8);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is required but unavailable.", e);
    }
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
