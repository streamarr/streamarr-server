package com.streamarr.server.services.auth;

import com.streamarr.server.repositories.auth.CredentialAttemptRepository;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialAttemptRetentionSweeper {

  static final Duration RETENTION = Duration.ofDays(30);

  private final CredentialAttemptRepository repository;
  private final Clock clock;

  // A short initial delay: an instance restarted more often than the delay would never sweep.
  @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT24H")
  public void deleteExpiredAttempts() {
    var cutoff = clock.instant().minus(RETENTION);
    var deleted = repository.deleteAttemptedBefore(cutoff);
    log.info("Deleted {} credential attempts attempted before {}", deleted, cutoff);
  }
}
