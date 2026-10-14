package com.streamarr.server.services.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
  @DisplayName("Should run as the System actor only while system work runs")
  void shouldRunAsSystemActorOnlyWhileSystemWorkRuns() {
    var running = new AtomicReference<Boolean>();

    SystemWork.runAsSystemActor(() -> running.set(SystemWork.isRunningAsSystemActor()));

    assertThat(running).hasValue(true);
    assertThat(SystemWork.isRunningAsSystemActor()).isFalse();
  }

  @Test
  @DisplayName("Should run tasks as the System actor when the executor comes from system work")
  void shouldRunTasksAsSystemActorWhenExecutorComesFromSystemWork() throws Exception {
    try (var executor = SystemWork.newExecutorRunningAsSystemActor("system-task-")) {
      var task =
          executor.submit(
              () -> Thread.currentThread().getName() + ":" + SystemWork.isRunningAsSystemActor());

      assertThat(task.get(BOUND_SECONDS, TimeUnit.SECONDS)).matches("system-task-\\d+:true");
    }
  }

  @Test
  @DisplayName("Should run tasks as the System actor when a fixed pool uses System actor threads")
  void shouldRunTasksAsSystemActorWhenFixedPoolUsesSystemActorThreads() throws Exception {
    try (var executor =
        Executors.newFixedThreadPool(1, SystemWork.threadsRunningAsSystemActor("pooled-"))) {
      var first = executor.submit(SystemWork::isRunningAsSystemActor);
      var second = executor.submit(SystemWork::isRunningAsSystemActor);

      assertThat(first.get(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("Should run as the System actor when a thread starts running as the System actor")
  void shouldRunAsSystemActorWhenThreadStartsRunningAsSystemActor() throws Exception {
    var running = new CompletableFuture<String>();

    SystemWork.startThreadRunningAsSystemActor(
        "system-thread",
        () ->
            running.complete(
                Thread.currentThread().getName() + ":" + SystemWork.isRunningAsSystemActor()));

    assertThat(running.get(BOUND_SECONDS, TimeUnit.SECONDS)).isEqualTo("system-thread:true");
  }

  @Test
  @DisplayName("Should not run as the System actor when system work starts an ordinary thread")
  void shouldNotRunAsSystemActorWhenSystemWorkStartsAnOrdinaryThread() throws Exception {
    var running = new CompletableFuture<Boolean>();

    SystemWork.runAsSystemActor(
        () ->
            Thread.ofVirtual().start(() -> running.complete(SystemWork.isRunningAsSystemActor())));

    assertThat(running.get(BOUND_SECONDS, TimeUnit.SECONDS)).isFalse();
  }
}
