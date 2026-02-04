package com.streamarr.server.services.library;

import com.streamarr.server.config.LibraryWatcherProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class PollingFileStabilityChecker implements FileStabilityChecker {

  private final Clock clock;
  private final LibraryWatcherProperties properties;
  private final Sleeper sleeper;

  @Override
  public boolean awaitStability(Path path) {
    long lastSize;
    try {
      lastSize = Files.size(path);
    } catch (IOException | SecurityException e) {
      log.warn("Cannot read file size for {}: {}", path, e.getMessage());
      return false;
    }

    var startTime = clock.instant();
    var lastChangeTime = startTime;
    var stabilizationPeriod = Duration.ofSeconds(properties.stabilizationPeriodSeconds());
    var maxWait = Duration.ofSeconds(properties.maxWaitSeconds());
    var pollInterval = Duration.ofSeconds(properties.pollIntervalSeconds());

    while (true) {
      try {
        sleeper.sleep(pollInterval);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Stability check interrupted for {}", path);
        return false;
      }

      long currentSize;
      try {
        currentSize = Files.size(path);
      } catch (IOException | SecurityException e) {
        log.warn("File became inaccessible during stability check: {}", path);
        return false;
      }

      var now = clock.instant();

      if (currentSize != lastSize) {
        lastSize = currentSize;
        lastChangeTime = now;
      }

      if (Duration.between(lastChangeTime, now).compareTo(stabilizationPeriod) >= 0) {
        log.info("File stabilized: {}", path);
        return true;
      }

      if (Duration.between(startTime, now).compareTo(maxWait) >= 0) {
        log.warn("Max wait exceeded for file: {}", path);
        return false;
      }
    }
  }
}
