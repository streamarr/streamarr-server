package com.streamarr.server.services.library.walk;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.Spliterator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Separates enumeration from admission: drains a walk on its own virtual thread into an unbounded
 * in-memory queue of paths, and streams the queue. The walk therefore runs at its own pace, as it
 * does when the scan submits every file at once, however slowly the consumer pulls. An admission
 * strategy that waits between pulls then never holds a directory open part-way through its
 * listing, so an entry renamed or removed while the strategy waits cannot end the walk.
 *
 * <p>A failure of the walk reaches the consumer after the entries drained before it, as the walk's
 * own exception, exactly where a direct pull would have thrown it. Closing the returned stream
 * stops the drain after its current entry and waits for it; the caller still closes the source.
 */
public final class WalkPrefetch {

  private WalkPrefetch() {}

  public static <T> Stream<T> drainAhead(Stream<T> source) {
    var drain = new Drain<T>(source);
    drain.start();
    return StreamSupport.stream(drain, false).onClose(drain::stop);
  }

  private sealed interface Entry<T> {
    record Item<T>(T value) implements Entry<T> {}

    record Failure<T>(RuntimeException exception) implements Entry<T> {}

    record End<T>() implements Entry<T> {}
  }

  private static final class Drain<T> implements Spliterator<T> {

    private final Stream<T> source;
    private final LinkedBlockingQueue<Entry<T>> queue = new LinkedBlockingQueue<>();
    private volatile boolean stopped;
    private Thread thread;
    private boolean finished;

    private Drain(Stream<T> source) {
      this.source = source;
    }

    private void start() {
      thread = Thread.ofVirtual().name("poc-walk-prefetch").start(this::drain);
    }

    private void drain() {
      try {
        var iterator = source.iterator();
        while (!stopped && iterator.hasNext()) {
          queue.add(new Entry.Item<>(iterator.next()));
        }

        queue.add(new Entry.End<>());
      } catch (RuntimeException failure) {
        queue.add(new Entry.Failure<>(failure));
      } catch (Error error) {
        queue.add(new Entry.Failure<>(new IllegalStateException("Library walk failed", error)));
        throw error;
      }
    }

    private void stop() {
      stopped = true;
      try {
        thread.join();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
      if (finished) {
        return false;
      }

      return switch (take()) {
        case Entry.Item<T>(var value) -> {
          action.accept(value);
          yield true;
        }
        case Entry.Failure<T>(var exception) -> {
          finished = true;
          throw exception;
        }
        case Entry.End<T> _ -> {
          finished = true;
          yield false;
        }
      };
    }

    private Entry<T> take() {
      try {
        return queue.take();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        throw new UncheckedIOException(new InterruptedIOException("Library walk interrupted"));
      }
    }

    @Override
    public Spliterator<T> trySplit() {
      return null;
    }

    @Override
    public long estimateSize() {
      return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
      return Spliterator.NONNULL;
    }
  }
}
