package com.streamarr.server.services.library.admission;

import java.util.List;
import java.util.stream.Stream;

/**
 * Decides when each item of a scan or refresh fork/join starts. Exactly one implementation is a
 * bean, selected by {@code poc.admission} ({@code unbounded} when unset).
 *
 * <p>Contract for implementations:
 *
 * <ul>
 *   <li>{@code items} is lazy. For a scan it is the live {@code Files.walk} when {@link
 *       #waitsBetweenPulls()} is false: pulling the next item advances the walk. When it is true the
 *       scan drains the walk ahead into memory, so waiting between pulls never pauses the walk.
 *   <li>Every pulled item must be run exactly once, through an {@link AdmissionTicket} from {@link
 *       AdmissionRuntime#admit()} so that the per-task signals and the poc metrics see it.
 *   <li>Return only after every admitted task finished. Return each failure (the exception a task
 *       threw) in the order the items were pulled; the caller turns them into the scan's or
 *       refresh's failure exactly as before.
 *   <li>If the calling thread is interrupted while waiting, re-assert the interrupt flag and throw
 *       {@link InterruptedException}.
 *   <li>An exception thrown by the stream itself (the walk) propagates unchanged after the tasks
 *       already admitted have finished.
 * </ul>
 *
 * <p>When {@code task} is a {@link StagedTask}, an implementation may call its stages separately;
 * otherwise it must use {@link AdmittedTask#run}.
 */
public interface FileAdmission {

  /** The {@code poc.admission} value that selects this implementation. */
  String name();

  <T> List<Throwable> processAll(Workload workload, Stream<T> items, AdmittedTask<T> task)
      throws InterruptedException;

  /**
   * True when this strategy may wait between pulling one item and the next. The scan then
   * enumerates the library ahead of admission instead of letting the strategy pace the walk.
   */
  default boolean waitsBetweenPulls() {
    return true;
  }
}
