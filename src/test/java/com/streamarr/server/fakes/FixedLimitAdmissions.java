package com.streamarr.server.fakes;

import com.netflix.concurrency.limits.limit.FixedLimit;
import com.streamarr.server.services.concurrency.AdaptiveAdmission;
import com.streamarr.server.services.concurrency.AdaptiveAdmissions;
import com.streamarr.server.services.concurrency.AdmissionListener;
import com.streamarr.server.services.concurrency.AdmittedTaskReport;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Opens admissions whose limit stays at a fixed size, so a test can pin how many tasks run, and
 * lets the test wait until admitted tasks have reported.
 */
public class FixedLimitAdmissions extends AdaptiveAdmissions {

  private final int limit;
  private final Semaphore reports = new Semaphore(0);

  public FixedLimitAdmissions(int limit) {
    this.limit = limit;
  }

  @Override
  public AdaptiveAdmission open(AdmissionListener listener) {
    return AdaptiveAdmission.builder()
        .limit(FixedLimit.of(limit))
        .nanoClock(System::nanoTime)
        .listener(
            new AdmissionListener() {
              @Override
              public void onAdmitted() {
                listener.onAdmitted();
              }

              @Override
              public void onReported(AdmittedTaskReport report) {
                listener.onReported(report);
                reports.release();
              }

              @Override
              public void onClosed() {
                listener.onClosed();
              }
            })
        .build();
  }

  /** Waits until {@code count} more admitted tasks have reported, failing after {@code bound}. */
  public void awaitReports(int count, Duration bound) throws InterruptedException {
    if (!reports.tryAcquire(count, bound.toNanos(), TimeUnit.NANOSECONDS)) {
      throw new AssertionError(count + " admitted tasks did not report within " + bound);
    }
  }
}
