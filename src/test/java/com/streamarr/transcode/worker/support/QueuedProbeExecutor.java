package com.streamarr.transcode.worker.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class QueuedProbeExecutor extends AbstractExecutorService {

  private final ExecutorService running = Executors.newVirtualThreadPerTaskExecutor();
  private final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
  private final CompletableFuture<Void> closing = new CompletableFuture<>();

  @Override
  public synchronized void execute(Runnable command) {
    if (isShutdown()) {
      throw new RejectedExecutionException("Probe scope is closed");
    }

    queued.add(command);
  }

  public Future<?> runNext() {
    var task = queued.poll();
    assertThat(task).as("a probe is queued for execution").isNotNull();
    return running.submit(task);
  }

  public void drain() throws Exception {
    while (!queued.isEmpty()) {
      runNext().get(5, TimeUnit.SECONDS);
    }
  }

  public void awaitClosing() throws Exception {
    closing.get(5, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void shutdown() {
    running.shutdown();
  }

  @Override
  public synchronized List<Runnable> shutdownNow() {
    var abandoned = new ArrayList<>(queued);
    queued.clear();
    abandoned.addAll(running.shutdownNow());
    return abandoned;
  }

  @Override
  public boolean isShutdown() {
    return running.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return running.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return running.awaitTermination(timeout, unit);
  }

  @Override
  public void close() {
    shutdown();
    closing.complete(null);
    try {
      assertThat(awaitTermination(5, TimeUnit.SECONDS))
          .as("all probe tasks finish before their scope closes")
          .isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while closing the probe scope", exception);
    }
  }
}
