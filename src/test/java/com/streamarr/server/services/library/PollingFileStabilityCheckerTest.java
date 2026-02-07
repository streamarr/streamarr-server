package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.LibraryWatcherProperties;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Polling File Stability Checker Tests")
class PollingFileStabilityCheckerTest {

  private FileSystem fileSystem;
  private AtomicReference<Instant> currentTime;

  @BeforeEach
  void setUp() {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    currentTime = new AtomicReference<>(Instant.parse("2024-01-01T00:00:00Z"));
  }

  @AfterEach
  void tearDown() throws IOException {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should return true when file is already stable")
  void shouldReturnTrueWhenFileIsAlreadyStable() throws IOException {
    var path = createFile("/stable.mkv", 1024);
    var checker = buildChecker(10, 5, 3600);

    var result = checker.waitForStability(path);

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("Should return true when file stabilizes after growing")
  void shouldReturnTrueWhenFileStabilizesAfterGrowing() throws IOException {
    var path = createFile("/growing.mkv", 100);
    var growCount = new int[] {0};

    var checker =
        buildChecker(
            10,
            5,
            3600,
            duration -> {
              currentTime.updateAndGet(t -> t.plus(duration));
              growCount[0]++;
              if (growCount[0] <= 2) {
                try {
                  Files.write(path, new byte[100 + growCount[0] * 50]);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              }
            });

    var result = checker.waitForStability(path);

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("Should return false when file does not exist")
  void shouldReturnFalseWhenFileDoesNotExist() {
    var path = fileSystem.getPath("/nonexistent.mkv");
    var checker = buildChecker(10, 5, 3600);

    var result = checker.waitForStability(path);

    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("Should return false when file is deleted during check")
  void shouldReturnFalseWhenFileIsDeletedDuringCheck() throws IOException {
    var path = createFile("/disappearing.mkv", 1024);

    var checker =
        buildChecker(
            10,
            5,
            3600,
            duration -> {
              currentTime.updateAndGet(t -> t.plus(duration));
              try {
                Files.deleteIfExists(path);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });

    var result = checker.waitForStability(path);

    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("Should return false when max wait exceeded")
  void shouldReturnFalseWhenMaxWaitExceeded() throws IOException {
    var path = createFile("/forever-growing.mkv", 100);
    var pollCount = new int[] {0};

    var checker =
        buildChecker(
            30,
            5,
            60,
            duration -> {
              currentTime.updateAndGet(t -> t.plus(duration));
              pollCount[0]++;
              try {
                Files.write(path, new byte[100 + pollCount[0] * 10]);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });

    var result = checker.waitForStability(path);

    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("Should return false when file is inaccessible")
  void shouldReturnFalseWhenFileIsInaccessible() {
    var path = fileSystem.getPath("/no-access.mkv");
    var checker = buildChecker(10, 5, 3600);

    var result = checker.waitForStability(path);

    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("Should return true when file is zero bytes")
  void shouldReturnTrueWhenFileIsZeroBytes() throws IOException {
    var path = createFile("/empty.mkv", 0);
    var checker = buildChecker(10, 5, 3600);

    var result = checker.waitForStability(path);

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("Should return false when interrupted")
  void shouldReturnFalseWhenInterrupted() throws IOException {
    var path = createFile("/interrupted.mkv", 1024);

    Sleeper interruptingSleeper =
        duration -> {
          currentTime.updateAndGet(t -> t.plus(duration));
          throw new InterruptedException("shutdown");
        };

    var properties = new LibraryWatcherProperties(10, 5, 3600);
    var checker =
        new PollingFileStabilityChecker(
            new MutableClock(currentTime), properties, interruptingSleeper);

    var result = checker.waitForStability(path);

    assertThat(result).isFalse();
    assertThat(Thread.currentThread().isInterrupted()).isTrue();

    // Clear interrupt flag for test cleanup
    Thread.interrupted();
  }

  private Path createFile(String name, int size) throws IOException {
    var path = fileSystem.getPath(name);
    Files.write(path, new byte[size]);
    return path;
  }

  private PollingFileStabilityChecker buildChecker(
      int stabilizationSeconds, int pollSeconds, int maxWaitSeconds) {
    return buildChecker(
        stabilizationSeconds,
        pollSeconds,
        maxWaitSeconds,
        duration -> currentTime.updateAndGet(t -> t.plus(duration)));
  }

  private PollingFileStabilityChecker buildChecker(
      int stabilizationSeconds, int pollSeconds, int maxWaitSeconds, Sleeper sleeper) {
    var properties =
        new LibraryWatcherProperties(stabilizationSeconds, pollSeconds, maxWaitSeconds);
    return new PollingFileStabilityChecker(new MutableClock(currentTime), properties, sleeper);
  }

  private static class MutableClock extends Clock {
    private final AtomicReference<Instant> currentTime;

    MutableClock(AtomicReference<Instant> currentTime) {
      this.currentTime = currentTime;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return currentTime.get();
    }
  }
}
