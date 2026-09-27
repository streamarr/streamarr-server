package com.streamarr.server.poc.adaptive;

import com.streamarr.server.services.library.admission.AdmittedTask;
import com.streamarr.server.services.library.admission.FileAdmission;
import com.streamarr.server.services.library.admission.Workload;
import com.streamarr.server.services.library.admission.adaptive.ScopedAdaptiveFileAdmission;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Throwaway harness trigger for the C1S stop path: requests a stop once {@code stopAfter} items of
 * a run have ended, so a benchmark cell can stop a scan or refresh while real database and TMDB
 * calls are in flight.
 */
@Slf4j
final class StopAfterItemsFileAdmission implements FileAdmission {

  private final ScopedAdaptiveFileAdmission delegate;
  private final int stopAfter;

  StopAfterItemsFileAdmission(ScopedAdaptiveFileAdmission delegate, int stopAfter) {
    this.delegate = delegate;
    this.stopAfter = stopAfter;
  }

  @Override
  public String name() {
    return delegate.name();
  }

  @Override
  public boolean waitsBetweenPulls() {
    return delegate.waitsBetweenPulls();
  }

  @Override
  public <T> List<Throwable> processAll(Workload workload, Stream<T> items, AdmittedTask<T> task)
      throws InterruptedException {
    var ended = new AtomicInteger();
    AdmittedTask<T> stopping =
        item -> {
          try {
            return task.run(item);
          } finally {
            if (ended.incrementAndGet() == stopAfter) {
              log.info("POC stop requested for the {} after {} items ended", workload, stopAfter);
              delegate.requestStop();
            }
          }
        };
    return delegate.processAll(workload, items, stopping);
  }
}
