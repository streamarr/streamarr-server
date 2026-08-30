package com.streamarr.server.services.auth;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LogoutService {

  private final RefreshTokenService refreshTokenService;
  private final DeviceRegistrationLifecycle deviceRegistrationLifecycle;
  private final Clock clock;

  @Transactional
  public void logout(String rawRefreshToken) {
    refreshTokenService
        .logout(rawRefreshToken)
        .filter(LoggedOutSession::deviceBound)
        .ifPresent(
            session ->
                deviceRegistrationLifecycle.revoke(
                    session.registrationId().orElseThrow(),
                    session.accountId(),
                    "device signed out",
                    clock.instant()));
  }
}
