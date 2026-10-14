package com.streamarr.server.services.architecturefixture;

import com.streamarr.server.services.audit.SystemWork;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

public final class SystemWorkThreadFixtures {

  private SystemWorkThreadFixtures() {}

  public static final class OrdinaryVirtualThreadStart {

    public Thread start(Runnable work) {
      return Thread.startVirtualThread(work);
    }
  }

  public static final class OrdinaryThreadBuilder {

    public ThreadFactory factory() {
      return Thread.ofVirtual().factory();
    }
  }

  public static final class OrdinaryThreadConstructor {

    public Thread thread(Runnable work) {
      return new Thread(work);
    }
  }

  public static final class OrdinaryExecutorReference {

    public Supplier<ExecutorService> executors() {
      return Executors::newVirtualThreadPerTaskExecutor;
    }
  }

  public static final class OrdinaryFixedPool {

    public ExecutorService executor() {
      return Executors.newFixedThreadPool(2);
    }
  }

  public static final class DefaultAsyncExecutor {

    public CompletableFuture<Void> run(Runnable work) {
      return CompletableFuture.runAsync(work);
    }
  }

  public static final class SystemWorkThreads {

    public ExecutorService fixedPool() {
      return Executors.newFixedThreadPool(2, SystemWork.threadsRunningAsSystemActor("fixture-"));
    }

    public ExecutorService threadPerTask() {
      return SystemWork.newExecutorRunningAsSystemActor("fixture-");
    }

    public CompletableFuture<Void> run(Runnable work, ExecutorService executor) {
      return CompletableFuture.runAsync(work, executor);
    }
  }
}
