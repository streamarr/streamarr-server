package com.streamarr.server.services.concurrency;

/** The work that one admission covers, for one subject such as a file or a title. */
@FunctionalInterface
public interface AdmittedTask<S> {

  /** Runs the task and returns what it reports when it finishes without throwing. */
  AdmittedTaskReport run(S subject) throws Exception;
}
