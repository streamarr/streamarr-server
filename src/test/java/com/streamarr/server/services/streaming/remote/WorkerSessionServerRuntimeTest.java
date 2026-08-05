package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Server;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Worker Session Server Runtime Tests")
class WorkerSessionServerRuntimeTest {

  @Test
  @DisplayName("Should shut down the startup executor when server startup throws an exception")
  void shouldShutDownStartupExecutorWhenServerStartupThrowsException() {
    var runtime = runtime();
    var executor = Executors.newSingleThreadExecutor();

    assertThatThrownBy(
            () ->
                runtime.start(
                    executor,
                    _ -> {
                      throw new IOException("bind failed");
                    }))
        .isInstanceOf(IOException.class)
        .hasMessage("bind failed");

    assertThat(executor.isShutdown()).isTrue();
  }

  @Test
  @DisplayName("Should shut down the startup executor when server startup throws an error")
  void shouldShutDownStartupExecutorWhenServerStartupThrowsError() {
    var runtime = runtime();
    var executor = Executors.newSingleThreadExecutor();

    assertThatThrownBy(
            () ->
                runtime.start(
                    executor,
                    _ -> {
                      throw new AssertionError("native startup failed");
                    }))
        .isInstanceOf(AssertionError.class)
        .hasMessage("native startup failed");

    assertThat(executor.isShutdown()).isTrue();
  }

  @Test
  @DisplayName("Should reject a second start without replacing the server or leaking its executor")
  void shouldRejectSecondStartWithoutReplacingServerOrLeakingItsExecutor() throws Exception {
    var originalServer = new ControllableServer(AwaitOutcome.TIMES_OUT);
    var originalExecutor = Executors.newSingleThreadExecutor();
    var rejectedExecutor = Executors.newSingleThreadExecutor();
    var runtime = startedRuntime(originalServer, originalExecutor);

    try {
      assertThatThrownBy(() -> runtime.start(rejectedExecutor, _ -> new ControllableServer(null)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Worker session server is already started");

      assertThat(runtime.server()).isSameAs(originalServer);
      assertThat(originalExecutor.isShutdown()).isFalse();
      assertThat(rejectedExecutor.isShutdown()).isTrue();
    } finally {
      runtime.close();
      rejectedExecutor.shutdownNow();
    }
  }

  @Test
  @DisplayName("Should warn and release resources when server shutdown times out")
  void shouldWarnAndReleaseResourcesWhenServerShutdownTimesOut() throws Exception {
    var server = new ControllableServer(AwaitOutcome.TIMES_OUT);
    var executor = Executors.newSingleThreadExecutor();
    var runtime = startedRuntime(server, executor);
    var logger = (Logger) LoggerFactory.getLogger(WorkerSessionServer.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      runtime.close();
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly("Worker session gRPC server did not terminate within 5s");
    assertThat(server.shutdownRequested).isTrue();
    assertThat(executor.isShutdown()).isTrue();
  }

  @Test
  @DisplayName("Should restore the interrupted flag when server shutdown is interrupted")
  void shouldRestoreInterruptedFlagWhenServerShutdownIsInterrupted() throws Exception {
    var server = new ControllableServer(AwaitOutcome.INTERRUPTS);
    var executor = Executors.newSingleThreadExecutor();
    var runtime = startedRuntime(server, executor);

    try {
      runtime.close();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(server.shutdownRequested).isTrue();
      assertThat(executor.isShutdown()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private WorkerSessionServerRuntime startedRuntime(Server server, ExecutorService executorService)
      throws IOException {
    var runtime = runtime();
    runtime.start(executorService, _ -> server);
    return runtime;
  }

  private WorkerSessionServerRuntime runtime() {
    return new WorkerSessionServerRuntime(LoggerFactory.getLogger(WorkerSessionServer.class));
  }

  private enum AwaitOutcome {
    TIMES_OUT,
    INTERRUPTS
  }

  private static final class ControllableServer extends Server {

    private final AwaitOutcome awaitOutcome;
    private boolean shutdownRequested;

    private ControllableServer(AwaitOutcome awaitOutcome) {
      this.awaitOutcome = awaitOutcome;
    }

    @Override
    public Server start() {
      return this;
    }

    @Override
    public Server shutdown() {
      shutdownRequested = true;
      return this;
    }

    @Override
    public Server shutdownNow() {
      shutdownRequested = true;
      return this;
    }

    @Override
    public boolean isShutdown() {
      return shutdownRequested;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      if (awaitOutcome == AwaitOutcome.INTERRUPTS) {
        throw new InterruptedException("shutdown interrupted");
      }
      return false;
    }

    @Override
    public void awaitTermination() throws InterruptedException {
      awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
