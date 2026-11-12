package com.streamarr.server.config.observability;

import com.streamarr.server.services.audit.SystemWork;
import io.micrometer.registry.otlp.OtlpMetricsSender;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.NonNull;

/**
 * Sends metrics on the caller's thread until the server begins to stop. Then it interrupts the
 * sends in flight, which the meter registry's close would wait for, and gives every later send, the
 * close's last publish among them, what remains of one second.
 */
final class ShutdownBoundedMetricsSender implements OtlpMetricsSender {

  static final Duration SHUTDOWN_BOUND = Duration.ofSeconds(1);

  private final OtlpMetricsSender delegate;
  private final Set<Thread> sending = ConcurrentHashMap.newKeySet();
  private final ThreadFactory boundedSends =
      SystemWork.newThreadFactoryRunningAsSystemActor("metrics-send-at-shutdown-");
  private volatile long deadlineNanos;
  private volatile boolean shuttingDown;

  ShutdownBoundedMetricsSender(@NonNull OtlpMetricsSender delegate) {
    this.delegate = delegate;
  }

  // A sender marks its thread before it reads the shutdown flag, and the shutdown sets the flag
  // before it interrupts the marked threads, so every send either sees the flag or is interrupted.
  @Override
  public void send(Request request) throws Exception {
    var thread = Thread.currentThread();
    sending.add(thread);
    try {
      if (shuttingDown) {
        sendWithinTheShutdownBound(request);
        return;
      }

      delegate.send(request);
    } finally {
      sending.remove(thread);
    }
  }

  void beginShutdown() {
    deadlineNanos = System.nanoTime() + SHUTDOWN_BOUND.toNanos();
    shuttingDown = true;
    sending.forEach(Thread::interrupt);
  }

  private void sendWithinTheShutdownBound(Request request) throws Exception {
    var remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      throw new TimeoutException("The server stops, and its metrics sends had " + SHUTDOWN_BOUND);
    }

    var sent = new CompletableFuture<Void>();
    var sender =
        boundedSends.newThread(
            () -> {
              try {
                delegate.send(request);
                sent.complete(null);
              } catch (Exception failure) {
                sent.completeExceptionally(failure);
              }
            });
    sender.start();
    try {
      sent.get(remaining, TimeUnit.NANOSECONDS);
    } catch (TimeoutException | InterruptedException stopped) {
      sender.interrupt();
      throw stopped;
    } catch (ExecutionException failure) {
      // The sender above fails the future only with the Exception that the store threw.
      throw (Exception) failure.getCause();
    }
  }
}
