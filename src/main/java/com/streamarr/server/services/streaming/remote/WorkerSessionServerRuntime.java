package com.streamarr.server.services.streaming.remote;

import io.grpc.Server;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

final class WorkerSessionServerRuntime {

  private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

  private final Logger logger;

  @FunctionalInterface
  interface ServerStarter {
    Server start(ExecutorService executor) throws IOException;
  }

  private Server server;
  private ExecutorService executor;

  WorkerSessionServerRuntime(Logger logger) {
    this.logger = Objects.requireNonNull(logger);
  }

  synchronized void start(ExecutorService startingExecutor, ServerStarter starter)
      throws IOException {
    Objects.requireNonNull(startingExecutor);
    Objects.requireNonNull(starter);
    var executorTransferred = false;
    try {
      if (server != null) {
        throw new IllegalStateException("Worker session server is already started");
      }
      server = starter.start(startingExecutor);
      executor = startingExecutor;
      executorTransferred = true;
    } finally {
      if (!executorTransferred) {
        startingExecutor.shutdownNow();
      }
    }
  }

  synchronized boolean isStarted() {
    return server != null;
  }

  synchronized Server server() {
    if (server == null) {
      throw new IllegalStateException("Worker session server is not started");
    }
    return server;
  }

  synchronized void close() {
    if (server == null) {
      return;
    }

    server.shutdownNow();
    try {
      if (!server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warn(
            "Worker session gRPC server did not terminate within {}s", SHUTDOWN_TIMEOUT_SECONDS);
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
    executor.shutdownNow();
    server = null;
    executor = null;
  }
}
