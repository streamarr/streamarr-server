package com.streamarr.server.services.library;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * The end of one execution's admission: a stop stored with the library operation, or a shutdown
 * that drains the execution (ADR 0027). Once the end is reached, the execution admits no further
 * task, and a scan stops waiting for probes, which neither a stop nor a shutdown waits for. Tasks
 * that the execution admitted earlier still finish their foreground steps.
 */
@FunctionalInterface
public interface AdmissionEnd {

  /** Reads whether the execution admits no further task. */
  boolean isReached();

  /**
   * Returns the subjects that remain until the end is reached. The end is read each time the
   * admission asks whether a next subject exists, which happens only after the admission is
   * granted. A subject that the admission found stays available to take, even if the end is reached
   * in between.
   */
  default <S> Iterator<S> untilReached(Iterator<S> subjects) {
    return new Iterator<>() {
      private boolean found;

      @Override
      public boolean hasNext() {
        found = found || !isReached() && subjects.hasNext();
        return found;
      }

      @Override
      public S next() {
        if (!hasNext()) {
          throw new NoSuchElementException("The execution admits no further task");
        }

        found = false;
        return subjects.next();
      }
    };
  }
}
