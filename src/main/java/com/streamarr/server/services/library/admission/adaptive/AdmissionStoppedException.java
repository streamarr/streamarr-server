package com.streamarr.server.services.library.admission.adaptive;

import com.streamarr.server.services.library.admission.Workload;

/** A stop kept at least one item of the scan or refresh from running. */
public class AdmissionStoppedException extends RuntimeException {

  public AdmissionStoppedException(Workload workload) {
    super("The " + workload + " was stopped before every item ran");
  }
}
