package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Counting Sleeper Tests")
class CountingSleeperTest {

  private static final Duration INTERVAL = Duration.ofSeconds(2);

  @Test
  @DisplayName("Should advance the clock and run the hook with the sleep count when it sleeps")
  void shouldAdvanceTheClockAndRunTheHookWithTheSleepCountWhenItSleeps() throws Exception {
    var clock = new MutableClock();
    var start = clock.instant();
    var sleeper = new CountingSleeper(clock);
    var observed = new ArrayList<Instant>();
    var counts = new ArrayList<Integer>();
    sleeper.onSleep(
        count -> {
          counts.add(count);
          observed.add(clock.instant());
        });

    sleeper.sleep(INTERVAL);
    sleeper.sleep(INTERVAL);

    assertThat(counts).containsExactly(1, 2);
    assertThat(observed)
        .containsExactly(start.plus(INTERVAL), start.plus(INTERVAL.multipliedBy(2)));
    assertThat(sleeper.sleeps()).isEqualTo(List.of(INTERVAL, INTERVAL));
  }

  @Test
  @DisplayName("Should throw and clear the interrupt when the sleeping thread is interrupted")
  void shouldThrowAndClearTheInterruptWhenTheSleepingThreadIsInterrupted() {
    var sleeper = new CountingSleeper();

    Thread.currentThread().interrupt();

    assertThatThrownBy(() -> sleeper.sleep(INTERVAL)).isInstanceOf(InterruptedException.class);
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
    assertThat(sleeper.sleeps()).isEmpty();
  }

  @Test
  @DisplayName("Should fail when a wait loop sleeps a thousand and one times")
  void shouldFailWhenAWaitLoopSleepsAThousandAndOneTimes() throws Exception {
    var sleeper = new CountingSleeper();
    for (var sleep = 0; sleep < 1_000; sleep++) {
      sleeper.sleep(INTERVAL);
    }

    assertThatThrownBy(() -> sleeper.sleep(INTERVAL))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("never let the wait end");
  }
}
