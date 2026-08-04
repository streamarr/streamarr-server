package com.streamarr.server.services.auth;

import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes pairing rows past their TTL regardless of terminal status. Nothing reads a consumed or
 * denied row after the flow ends — the durable audit is {@code device_name} on {@code auth_session}
 * — so retention here would only grow the table and the guessable-code surface.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceAuthorizationSweeper {

  private final DeviceAuthorizationRepository authorizationRepository;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${auth.device.sweep-interval:15m}")
  public void sweep() {
    var deleted = authorizationRepository.deleteExpired(clock.instant());
    if (deleted > 0) {
      log.debug("Deleted {} expired device authorization rows.", deleted);
    }
  }
}
