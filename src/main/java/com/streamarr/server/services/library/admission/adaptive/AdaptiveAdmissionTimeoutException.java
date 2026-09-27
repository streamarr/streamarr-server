package com.streamarr.server.services.library.admission.adaptive;

import com.streamarr.server.services.library.admission.Workload;
import java.time.Duration;

/** No admission permit was freed within the acquire timeout; the scan or refresh stopped. */
public class AdaptiveAdmissionTimeoutException extends RuntimeException {

  public AdaptiveAdmissionTimeoutException(Workload workload, Duration timeout) {
    super("No " + workload + " admission permit was freed within " + timeout);
  }
}
