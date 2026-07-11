package com.streamarr.server.services.auth;

import static org.mockito.Mockito.mock;

import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.RefreshTokenRepository;
import com.streamarr.server.repositories.streaming.StreamSessionEnforcementRepository;

final class SessionRevocationTestFixture {

  private SessionRevocationTestFixture() {}

  static SessionRevocationService create(
      AuthSessionRepository sessionRepository, RefreshTokenRepository tokenRepository) {
    return new SessionRevocationService(
        sessionRepository, tokenRepository, mock(StreamSessionEnforcementRepository.class));
  }
}
