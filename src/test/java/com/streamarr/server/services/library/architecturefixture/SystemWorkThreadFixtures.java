package com.streamarr.server.services.library.architecturefixture;

import com.streamarr.server.services.audit.SystemWork;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

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

  public static final class OrdinaryParallelStream {

    public long count(List<String> items) {
      return items.parallelStream().count();
    }
  }

  public static final class OrdinaryParallelStreamSwitch {

    public long count(Stream<String> items) {
      return items.parallel().count();
    }
  }

  public static final class CommonForkJoinPool {

    public ExecutorService executor() {
      return ForkJoinPool.commonPool();
    }
  }

  public static final class OrdinaryForkJoinPool {

    public ExecutorService executor() {
      return new ForkJoinPool(2);
    }
  }

  public static final class OrdinaryThreadPoolConstructor {

    public ExecutorService executor() {
      return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }
  }

  public static final class OrdinaryScheduledPoolConstructor {

    public ExecutorService executor() {
      return new ScheduledThreadPoolExecutor(1);
    }
  }

  public static final class DefaultDelayedExecutor {

    public Executor executor() {
      return CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS);
    }
  }

  public static final class OrdinarySpringTaskExecutor {

    public Executor executor() {
      return new SimpleAsyncTaskExecutor();
    }
  }

  public static final class OrdinaryTimer {

    public Timer timer() {
      return new Timer();
    }
  }

  public static final class SystemWorkThreads {

    public ExecutorService fixedPool() {
      return Executors.newFixedThreadPool(
          2, SystemWork.newThreadFactoryRunningAsSystemActor("fixture-"));
    }

    public ExecutorService threadPerTask() {
      return SystemWork.newExecutorRunningAsSystemActor("fixture-");
    }

    public CompletableFuture<Void> run(Runnable work, ExecutorService executor) {
      return CompletableFuture.runAsync(work, executor);
    }

    public ExecutorService threadPool() {
      return new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(),
          SystemWork.newThreadFactoryRunningAsSystemActor("fixture-"));
    }

    public Executor delayed(ExecutorService executor) {
      return CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS, executor);
    }
  }
}
