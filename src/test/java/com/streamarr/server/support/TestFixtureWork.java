package com.streamarr.server.support;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs test code as the test fixture actor, so the transactions it begins name that actor in their
 * audit columns.
 *
 * <p>The binding covers the calling thread for the extent of the call. A thread that the work
 * starts does not inherit it, so a production thread that writes audited rows without system work
 * or a request still names no actor and is rejected, as in production. Test code that writes
 * audited rows from a thread it starts must run that work through this class.
 */
public final class TestFixtureWork {

  private static final ScopedValue<Boolean> RUNNING_AS_TEST_FIXTURE_ACTOR =
      ScopedValue.newInstance();

  private TestFixtureWork() {}

  public static void runAsTestFixtureActor(Runnable work) {
    ScopedValue.where(RUNNING_AS_TEST_FIXTURE_ACTOR, Boolean.TRUE).run(work);
  }

  public static <T, X extends Throwable> T callAsTestFixtureActor(ScopedValue.CallableOp<T, X> work)
      throws X {
    return ScopedValue.where(RUNNING_AS_TEST_FIXTURE_ACTOR, Boolean.TRUE).call(work);
  }

  /**
   * Returns an executor that runs each task as the test fixture actor on its own virtual thread.
   */
  public static ExecutorService newExecutorRunningAsTestFixtureActor() {
    var threads = Thread.ofVirtual().factory();
    return Executors.newThreadPerTaskExecutor(
        task -> threads.newThread(() -> runAsTestFixtureActor(task)));
  }

  /**
   * Returns a fixed pool of {@code threads} platform threads that run as the test fixture actor for
   * their whole life.
   */
  public static ExecutorService newFixedThreadPoolRunningAsTestFixtureActor(int threads) {
    var platformThreads = Executors.defaultThreadFactory();
    return Executors.newFixedThreadPool(
        threads, task -> platformThreads.newThread(() -> runAsTestFixtureActor(task)));
  }

  public static boolean isRunningAsTestFixtureActor() {
    return RUNNING_AS_TEST_FIXTURE_ACTOR.isBound();
  }
}
