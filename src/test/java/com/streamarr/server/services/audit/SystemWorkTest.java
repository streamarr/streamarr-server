package com.streamarr.server.services.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("UnitTest")
@DisplayName("System Work Tests")
class SystemWorkTest {

  private static final long BOUND_SECONDS = 5;

  @Test
  @DisplayName("Should not run as the System actor when no system work started the thread")
  void shouldNotRunAsSystemActorWhenNoSystemWorkStartedTheThread() {
    assertThat(SystemWork.isRunningAsSystemActor()).isFalse();
  }

  @Test
  @DisplayName("Should run as the System actor when system work runs")
  void shouldRunAsSystemActorWhenSystemWorkRuns() {
    var running = new AtomicBoolean();

    SystemWork.runAsSystemActor(() -> running.set(SystemWork.isRunningAsSystemActor()));

    assertThat(running).isTrue();
  }

  @Test
  @DisplayName("Should stop running as the System actor when system work returns")
  void shouldStopRunningAsSystemActorWhenSystemWorkReturns() {
    SystemWork.runAsSystemActor(() -> {});

    assertThat(SystemWork.isRunningAsSystemActor()).isFalse();
  }

  @Test
  @DisplayName("Should reject system work when it starts inside an open transaction")
  void shouldRejectSystemWorkWhenItStartsInsideAnOpenTransaction() {
    var ran = new AtomicBoolean();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> SystemWork.runAsSystemActor(() -> ran.set(true)))
          .isInstanceOf(SystemWorkInsideTransactionException.class);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    assertThat(ran).isFalse();
  }

  @Test
  @DisplayName("Should run tasks as the System actor when the executor comes from system work")
  void shouldRunTasksAsSystemActorWhenExecutorComesFromSystemWork()
      throws InterruptedException, ExecutionException, TimeoutException {
    try (var executor = SystemWork.newExecutorRunningAsSystemActor("system-task-")) {
      var observed = executor.submit(ObservedThread::current).get(BOUND_SECONDS, TimeUnit.SECONDS);

      assertThat(observed.name()).matches("system-task-\\d+");
      assertThat(observed.runningAsSystemActor()).isTrue();
    }
  }

  @Test
  @DisplayName("Should run tasks as the System actor when a fixed pool uses System actor threads")
  void shouldRunTasksAsSystemActorWhenFixedPoolUsesSystemActorThreads()
      throws InterruptedException, ExecutionException, TimeoutException {
    try (var executor =
        Executors.newFixedThreadPool(
            1, SystemWork.newThreadFactoryRunningAsSystemActor("pooled-"))) {
      var first = executor.submit(SystemWork::isRunningAsSystemActor);
      var second = executor.submit(SystemWork::isRunningAsSystemActor);

      assertThat(first.get(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("Should run as the System actor when a thread starts running as the System actor")
  void shouldRunAsSystemActorWhenThreadStartsRunningAsSystemActor()
      throws InterruptedException, ExecutionException, TimeoutException {
    var started = new CompletableFuture<ObservedThread>();

    SystemWork.startThreadRunningAsSystemActor(
        "system-thread", () -> started.complete(ObservedThread.current()));

    var observed = started.get(BOUND_SECONDS, TimeUnit.SECONDS);
    assertThat(observed.name()).isEqualTo("system-thread");
    assertThat(observed.runningAsSystemActor()).isTrue();
  }

  @Test
  @DisplayName("Should not run as the System actor when system work starts an ordinary thread")
  void shouldNotRunAsSystemActorWhenSystemWorkStartsAnOrdinaryThread()
      throws InterruptedException, ExecutionException, TimeoutException {
    var running = new CompletableFuture<Boolean>();

    SystemWork.runAsSystemActor(
        () ->
            Thread.ofVirtual().start(() -> running.complete(SystemWork.isRunningAsSystemActor())));

    assertThat(running.get(BOUND_SECONDS, TimeUnit.SECONDS)).isFalse();
  }

  private record ObservedThread(String name, boolean runningAsSystemActor) {

    static ObservedThread current() {
      return new ObservedThread(
          Thread.currentThread().getName(), SystemWork.isRunningAsSystemActor());
    }
  }
}
