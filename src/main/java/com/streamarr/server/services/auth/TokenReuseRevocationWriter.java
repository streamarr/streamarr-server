package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TokenReuseRevocationWriter {

  private final SessionRevocationService sessionRevocationService;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void revoke(UUID sessionId, Instant detectedAt) {
    sessionRevocationService.revoke(sessionId, SessionRevocationReason.TOKEN_REUSE, detectedAt);
  }
}
