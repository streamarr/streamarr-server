package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.Library;
import com.streamarr.server.fakes.FixedLimitAdmissions;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.services.concurrency.AdaptiveAdmissions;
import com.streamarr.server.services.concurrency.AdmittedTask;
import com.streamarr.server.services.concurrency.AdmittedTaskReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Library operation admissions")
class LibraryOperationAdmissionsTest {

  private static final String LIMIT = "streamarr.library.admission.limit";
  private static final String IN_FLIGHT = "streamarr.library.admission.in_flight";
  private static final String ADMITTED = "streamarr.library.admission.admitted";
  private static final String REPORTS = "streamarr.library.admission.reports";
  private static final ThreadFactory THREADS = Thread.ofVirtual().factory();

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  @DisplayName(
      "Should count the walker's admission in flight when it pulls a subject after an admitted task")
  void shouldCountTheWalkersAdmissionInFlightWhenItPullsASubjectAfterAnAdmittedTask()
      throws Exception {
    var admissions = admissionsWith(new AdaptiveAdmissions());
    var library = library();
    var secondPull = new CountDownLatch(1);
    var inFlightAtSecondPull = new AtomicReference<Double>();
    var subjects =
        new Iterator<String>() {
          private boolean pulled;

          @Override
          public boolean hasNext() {
            if (!pulled) {
              return true;
            }

            inFlightAtSecondPull.set(gauge(IN_FLIGHT, "scan", library));
            secondPull.countDown();
            return false;
          }

          @Override
          public String next() {
            pulled = true;
            return "file";
          }
        };

    try (var admission = admissions.openScanAdmission(library)) {
      assertThat(gauge(LIMIT, "scan", library)).isEqualTo(16.0);
      assertThat(gauge(IN_FLIGHT, "scan", library)).isZero();

      admission.forkAdmittedTasks(
          subjects,
          _ -> {
            secondPull.await();
            return AdmittedTaskReport.IGNORED;
          },
          THREADS);

      assertThat(inFlightAtSecondPull).hasValue(2.0);
      assertThat(gauge(IN_FLIGHT, "scan", library)).isZero();
    }
  }

  @Test
  @DisplayName("Should count each report by kind when admitted tasks return")
  void shouldCountEachReportByKindWhenAdmittedTasksReturn() throws Exception {
    var admissions = admissionsWith(new FixedLimitAdmissions(1));
    AdmittedTask<Integer> task =
        subject ->
            switch (subject) {
              case 0 -> AdmittedTaskReport.LATENCY;
              case 1 -> AdmittedTaskReport.IGNORED;
              case 2 -> AdmittedTaskReport.DROP;
              default -> throw new IllegalStateException("task failed");
            };

    try (var admission = admissions.openRefreshAdmission(library())) {
      admission.forkAdmittedTasks(List.of(0, 1, 2, 3).iterator(), task, THREADS);
    }

    assertThat(registry.get(ADMITTED).tag("operation", "refresh").counter().count()).isEqualTo(4.0);
    assertThat(reports("refresh", "latency")).isEqualTo(1.0);
    assertThat(reports("refresh", "ignored")).isEqualTo(2.0);
    assertThat(reports("refresh", "drop")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should remove the operation's gauges when its admission closes")
  void shouldRemoveTheOperationsGaugesWhenItsAdmissionCloses() {
    var admissions = admissionsWith(new AdaptiveAdmissions());
    var library = library();

    admissions.openRefreshAdmission(library).close();

    assertThat(registry.find(LIMIT).gauges()).isEmpty();
    assertThat(registry.find(IN_FLIGHT).gauges()).isEmpty();
  }

  @Test
  @DisplayName("Should publish separate gauges when two libraries scan at once")
  void shouldPublishSeparateGaugesWhenTwoLibrariesScanAtOnce() {
    var admissions = admissionsWith(new FixedLimitAdmissions(3));
    var first = library();
    var second = library();

    try (var _ = admissions.openScanAdmission(first)) {
      try (var _ = admissions.openScanAdmission(second)) {
        assertThat(registry.find(LIMIT).gauges()).hasSize(2);
        assertThat(gauge(LIMIT, "scan", second)).isEqualTo(3.0);
      }

      assertThat(registry.find(LIMIT).gauges())
          .singleElement()
          .satisfies(gauge -> assertThat(gauge.getId().getTag("library")).isEqualTo(idOf(first)));
    }
  }

  private LibraryOperationAdmissions admissionsWith(AdaptiveAdmissions admissions) {
    return LibraryOperationAdmissions.builder()
        .meterRegistry(registry)
        .admissions(admissions)
        .build();
  }

  private double gauge(String name, String operation, Library library) {
    return registry
        .get(name)
        .tag("operation", operation)
        .tag("library", idOf(library))
        .gauge()
        .value();
  }

  private double reports(String operation, String report) {
    return registry
        .get(REPORTS)
        .tag("operation", operation)
        .tag("report", report)
        .counter()
        .count();
  }

  private static Library library() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    library.setId(UUID.randomUUID());
    return library;
  }

  private static String idOf(Library library) {
    return library.getId().toString();
  }
}
