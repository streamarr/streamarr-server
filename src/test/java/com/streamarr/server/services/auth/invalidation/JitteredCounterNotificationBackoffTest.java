package com.streamarr.server.services.auth.invalidation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Jittered Counter Notification Backoff Tests")
class JitteredCounterNotificationBackoffTest {

  @Test
  @DisplayName("Should restore the interrupt flag when interrupted during sleep")
  void shouldRestoreInterruptFlagWhenInterruptedDuringSleep() throws InterruptedException {
    var backoff = new JitteredCounterNotificationBackoff();
    var interruptRestored = new AtomicBoolean();

    // A pre-set interrupt makes the sleep throw immediately — the shutdown path when stop()
    // lands during a backoff wait; a swallowed flag would hang context close for the full wait.
    var sleeper =
        Thread.ofVirtual()
            .start(
                () -> {
                  Thread.currentThread().interrupt();
                  backoff.sleep(30_000);
                  interruptRestored.set(Thread.currentThread().isInterrupted());
                });

    assertThat(sleeper.join(Duration.ofSeconds(5))).isTrue();
    assertThat(interruptRestored).isTrue();
  }

  @Test
  @DisplayName("Should sleep at least the base delay when not interrupted")
  void shouldSleepAtLeastTheBaseDelayWhenNotInterrupted() {
    var backoff = new JitteredCounterNotificationBackoff();

    var start = System.nanoTime();
    backoff.sleep(10);
    var elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMs).isGreaterThanOrEqualTo(10L);
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }
}
