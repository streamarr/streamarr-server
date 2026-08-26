package com.streamarr.server.services.auth;

import com.streamarr.server.repositories.auth.CredentialAttemptRepository;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CredentialAttemptRetentionSweeper {

  static final Duration RETENTION = Duration.ofDays(30);

  private final CredentialAttemptRepository repository;
  private final Clock clock;

  @Scheduled(initialDelayString = "PT24H", fixedDelayString = "PT24H")
  public void deleteExpiredAttempts() {
    repository.deleteAttemptedBefore(clock.instant().minus(RETENTION));
  }
}
