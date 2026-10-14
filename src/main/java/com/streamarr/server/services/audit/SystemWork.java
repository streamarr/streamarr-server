package com.streamarr.server.services.audit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Runs work that no user requested as the System actor, so the rows the work writes name the System
 * actor in their audit columns.
 *
 * <p>Work runs as the System actor only when the code that starts it says so. A request made
 * without a signed-in account never runs as the System actor.
 *
 * <p>The binding covers the calling thread for the extent of {@link #runAsSystemActor}. A thread
 * that the work starts does not inherit the binding. Threads that system work starts must come from
 * {@link #newExecutorRunningAsSystemActor}, {@link #threadsRunningAsSystemActor} or {@link
 * #startThreadRunningAsSystemActor}.
 *
 * <p>Spring {@code @Scheduled} methods do not run as the System actor. They write no audited rows.
 */
public final class SystemWork {

  private static final ScopedValue<Boolean> RUNNING_AS_SYSTEM_ACTOR = ScopedValue.newInstance();

  private SystemWork() {}

  /** Runs {@code work} on the calling thread as the System actor. */
  public static void runAsSystemActor(Runnable work) {
    ScopedValue.where(RUNNING_AS_SYSTEM_ACTOR, true).run(work);
  }

  /** Returns whether the calling thread runs as the System actor. */
  public static boolean isRunningAsSystemActor() {
    return RUNNING_AS_SYSTEM_ACTOR.orElse(false);
  }

  /**
   * Returns a factory of virtual threads that run as the System actor for their whole life. Each
   * thread's name is {@code namePrefix} followed by a counter.
   */
  public static ThreadFactory threadsRunningAsSystemActor(String namePrefix) {
    var threads = Thread.ofVirtual().name(namePrefix, 0).factory();
    return task -> threads.newThread(() -> runAsSystemActor(task));
  }

  /** Returns an executor that runs each task as the System actor on its own virtual thread. */
  public static ExecutorService newExecutorRunningAsSystemActor(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(threadsRunningAsSystemActor(namePrefix));
  }

  /** Starts a virtual thread named {@code name} that runs {@code work} as the System actor. */
  public static Thread startThreadRunningAsSystemActor(String name, Runnable work) {
    return Thread.ofVirtual().name(name).start(() -> runAsSystemActor(work));
  }
}
