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

  private sealed interface PollResult permits PollResult.Continue, PollResult.Stabilized, PollResult.Failed {
    record Continue(long lastSize, java.time.Instant lastChangeTime) implements PollResult {}
    record Stabilized() implements PollResult {}
    record Failed(String reason) implements PollResult {}
  }

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
      var result = pollOnce(path, lastSize, lastChangeTime, startTime, stabilizationPeriod, maxWait, pollInterval);

      switch (result) {
        case PollResult.Continue c -> {
          lastSize = c.lastSize();
          lastChangeTime = c.lastChangeTime();
        }
        case PollResult.Stabilized() -> {
          log.info("File stabilized: {}", path);
          return true;
        }
        case PollResult.Failed f -> {
          log.warn("{}: {}", f.reason(), path);
          return false;
        }
      }
    }
  }

  private PollResult pollOnce(
      Path path,
      long lastSize,
      java.time.Instant lastChangeTime,
      java.time.Instant startTime,
      Duration stabilizationPeriod,
      Duration maxWait,
      Duration pollInterval) {
    try {
      sleeper.sleep(pollInterval);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new PollResult.Failed("Stability check interrupted for");
    }

    long currentSize;
    try {
      currentSize = Files.size(path);
    } catch (IOException | SecurityException e) {
      return new PollResult.Failed("File became inaccessible during stability check");
    }

    var now = clock.instant();
    var updatedLastChangeTime = currentSize != lastSize ? now : lastChangeTime;

    if (Duration.between(updatedLastChangeTime, now).compareTo(stabilizationPeriod) >= 0) {
      return new PollResult.Stabilized();
    }

    if (Duration.between(startTime, now).compareTo(maxWait) >= 0) {
      return new PollResult.Failed("Max wait exceeded for file");
    }

    return new PollResult.Continue(currentSize, updatedLastChangeTime);
  }
}
