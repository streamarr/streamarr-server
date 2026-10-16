package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.concurrency.AdaptiveAdmission;
import com.streamarr.server.services.concurrency.AdaptiveAdmissions;
import com.streamarr.server.services.concurrency.AdmissionListener;
import com.streamarr.server.services.concurrency.AdmittedTaskReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Opens the admission of each scan and refresh, with a fresh limit per operation (ADR 0041), and
 * publishes its limit, its tasks in flight and its reports as metrics. The gauges are tagged with
 * the library and are removed when the operation's admission closes.
 */
@Component
@Builder
@RequiredArgsConstructor
public class LibraryOperationAdmissions {

  private static final String LIMIT = "streamarr.library.admission.limit";
  private static final String IN_FLIGHT = "streamarr.library.admission.in_flight";
  private static final String ADMITTED = "streamarr.library.admission.admitted";
  private static final String REPORTS = "streamarr.library.admission.reports";

  private final MeterRegistry meterRegistry;
  private final AdaptiveAdmissions admissions;

  /** Opens the admission of one scan of {@code library}; the caller closes it. */
  public AdaptiveAdmission openScanAdmission(Library library) {
    return open(LibraryOperationKind.SCAN, library.getId());
  }

  /** Opens the admission of one refresh of {@code library}; the caller closes it. */
  public AdaptiveAdmission openRefreshAdmission(Library library) {
    return open(LibraryOperationKind.REFRESH, library.getId());
  }

  private AdaptiveAdmission open(LibraryOperationKind kind, UUID libraryId) {
    var meters = new OperationMeters(kind);
    var admission = admissions.open(meters);
    meters.registerGauges(admission, libraryId);
    return admission;
  }

  private final class OperationMeters implements AdmissionListener {

    private final Tags operationTags;
    private final Counter admitted;
    private final List<Gauge> gauges = new CopyOnWriteArrayList<>();

    private OperationMeters(LibraryOperationKind kind) {
      this.operationTags = Tags.of("operation", kind.tagValue());
      this.admitted =
          Counter.builder(ADMITTED)
              .description("Tasks admitted by library operations")
              .tags(operationTags)
              .register(meterRegistry);
    }

    // The gauges hold the admission strongly until the operation closes and removes them.
    private void registerGauges(AdaptiveAdmission admission, UUID libraryId) {
      gauges.add(
          Gauge.builder(LIMIT, admission, AdaptiveAdmission::limit)
              .description("The current admission limit of a library operation")
              .tags(operationTags)
              .tag("library", libraryId.toString())
              .strongReference(true)
              .register(meterRegistry));
      gauges.add(
          Gauge.builder(IN_FLIGHT, admission, AdaptiveAdmission::inFlight)
              .description("Admitted tasks of a library operation that have not reported yet")
              .tags(operationTags)
              .tag("library", libraryId.toString())
              .strongReference(true)
              .register(meterRegistry));
    }

    @Override
    public void onAdmitted() {
      admitted.increment();
    }

    @Override
    public void onReported(AdmittedTaskReport report) {
      Counter.builder(REPORTS)
          .description("Reports of admitted library operation tasks, by kind")
          .tags(operationTags)
          .tag("report", report.name().toLowerCase(Locale.ROOT))
          .register(meterRegistry)
          .increment();
    }

    @Override
    public void onClosed() {
      gauges.forEach(meterRegistry::remove);
    }
  }
}
