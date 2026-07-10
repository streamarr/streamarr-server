package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TokenReuseRevocationWriter {

  private final AuthSessionRepository sessionRepository;
  private final RefreshTokenRepository tokenRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void revoke(UUID sessionId, Instant detectedAt) {
    sessionRepository.revoke(sessionId, SessionRevocationReason.TOKEN_REUSE, detectedAt);
    tokenRepository.revokeAllForSession(sessionId);
  }
}
