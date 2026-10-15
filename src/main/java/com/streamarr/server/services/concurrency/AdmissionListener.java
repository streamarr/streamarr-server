package com.streamarr.server.services.concurrency;

/** Receives what an {@link AdaptiveAdmission} admits and what its admitted tasks report. */
public interface AdmissionListener {

  AdmissionListener NONE = new AdmissionListener() {};

  /**
   * Called on the walking thread after an admitted subject's task is forked. The task may already
   * have reported.
   */
  default void onAdmitted() {}

  /** Called on the task's thread after its report returned its admission. */
  default void onReported(AdmittedTaskReport report) {}

  /** Called once when the admission closes. */
  default void onClosed() {}
}
