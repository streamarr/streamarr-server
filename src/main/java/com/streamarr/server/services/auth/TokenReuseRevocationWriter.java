package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.services.auth.events.CounterBumpedEvent;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TokenReuseRevocationWriter {

  private final AuthSessionRepository sessionRepository;
  private final RefreshTokenRepository tokenRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void revoke(UUID sessionId, Instant detectedAt) {
    var bumpedVersion =
        sessionRepository.revoke(sessionId, SessionRevocationReason.TOKEN_REUSE, detectedAt);
    tokenRepository.revokeAllForSession(sessionId, detectedAt);

    bumpedVersion.ifPresent(
        version -> eventPublisher.publishEvent(CounterBumpedEvent.session(sessionId, version)));
  }
}
