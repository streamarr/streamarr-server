package com.streamarr.server.services.concurrency;

import org.springframework.stereotype.Component;

/** Opens a fresh {@link AdaptiveAdmission} with the learned limit for each library operation. */
@Component
public class AdaptiveAdmissions {

  /** Opens an admission whose limit starts afresh and whose tasks are timed by the system clock. */
  public AdaptiveAdmission open(AdmissionListener listener) {
    return AdaptiveAdmission.builder()
        .limit(AdaptiveAdmission.learnedLimit())
        .nanoClock(System::nanoTime)
        .listener(listener)
        .build();
  }
}
