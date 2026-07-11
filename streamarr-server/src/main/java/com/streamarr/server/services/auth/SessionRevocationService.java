package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.streaming.StreamSessionEnforcementRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SessionRevocationService {

  private final AuthSessionRepository sessionRepository;
  private final RefreshTokenRepository tokenRepository;
  private final StreamSessionEnforcementRepository streamSessionRepository;

  @Transactional
  public void revoke(UUID sessionId, SessionRevocationReason reason, Instant revokedAt) {
    sessionRepository.lockById(sessionId);
    sessionRepository.revoke(sessionId, reason, revokedAt);
    tokenRepository.revokeAllForSession(sessionId, revokedAt);
    streamSessionRepository.terminalizeByAuthSession(sessionId, revokedAt);
  }
}
