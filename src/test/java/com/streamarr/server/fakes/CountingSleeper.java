package com.streamarr.server.fakes;

import com.streamarr.server.services.library.Sleeper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Returns from each sleep at once, after advancing an optional clock by the sleep's duration and
 * running a hook with the number of sleeps so far. A test changes state inside the hook, on the
 * sleeping thread, so the wait loop sees the change at its next check. A loop that sleeps more than
 * {@value #MAX_SLEEPS} times fails, because its test never let it finish.
 */
public final class CountingSleeper implements Sleeper {

  private static final int MAX_SLEEPS = 1_000;

  private final Consumer<Duration> advanceClock;
  private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
  private volatile IntConsumer onSleep = _ -> {};

  public CountingSleeper() {
    this.advanceClock = _ -> {};
  }

  public CountingSleeper(MutableClock clock) {
    this.advanceClock = clock::advance;
  }

  @Override
  public void sleep(Duration duration) throws InterruptedException {
    // A sleep that never blocks must still honor the interrupt that cancels its wait.
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    sleeps.add(duration);
    if (sleeps.size() > MAX_SLEEPS) {
      throw new AssertionError("Slept " + MAX_SLEEPS + " times; the test never let the wait end");
    }

    advanceClock.accept(duration);
    onSleep.accept(sleeps.size());
    Thread.yield();
  }

  public void onSleep(IntConsumer hook) {
    this.onSleep = hook;
  }

  public List<Duration> sleeps() {
    return List.copyOf(sleeps);
  }
}
