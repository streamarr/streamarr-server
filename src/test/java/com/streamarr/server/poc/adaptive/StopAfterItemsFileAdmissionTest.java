package com.streamarr.server.poc.adaptive;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.AdmittedTask;
import com.streamarr.server.services.library.admission.TaskOutcome;
import com.streamarr.server.services.library.admission.Workload;
import com.streamarr.server.services.library.admission.adaptive.AdaptiveLimitAlgorithm;
import com.streamarr.server.services.library.admission.adaptive.AdmissionStoppedException;
import com.streamarr.server.services.library.admission.adaptive.ScopedAdaptiveFileAdmission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("POC stop-after-items harness trigger tests")
class StopAfterItemsFileAdmissionTest {

  private final AdmissionRuntime runtime = new AdmissionRuntime();
  private final StopAfterItemsFileAdmission admission =
      new StopAfterItemsFileAdmission(
          new ScopedAdaptiveFileAdmission(
              AdaptiveLimitAlgorithm.GRADIENT2,
              runtime,
              Duration.ofMinutes(1),
              new SimpleMeterRegistry()),
          3);
  private final AtomicInteger started = new AtomicInteger();
  private final AtomicInteger interrupted = new AtomicInteger();

  private final AdmittedTask<Integer> shortItem =
      _ -> {
        started.incrementAndGet();
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          interrupted.incrementAndGet();
          throw e;
        }

        return TaskOutcome.WORKED;
      };

  @Test
  @DisplayName(
      "Should stop the run without interrupting an item once the configured number of items ended")
  void shouldStopRunWithoutInterruptingItemOnceConfiguredNumberOfItemsEnded() throws Exception {
    var failures = admission.processAll(Workload.SCAN, IntStream.range(0, 50).boxed(), shortItem);

    assertThat(failures).singleElement().isInstanceOf(AdmissionStoppedException.class);
    assertThat(started.get()).isGreaterThanOrEqualTo(3).isLessThan(50);
    assertThat(interrupted).hasValue(0);
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(started.get());
    assertThat(runtime.inFlight()).isZero();
    assertThat(admission.name()).isEqualTo("adaptive-gradient2-scope");
  }

  @Test
  @DisplayName("Should not stop a run whose items all end before the configured number")
  void shouldNotStopRunWhoseItemsAllEndBeforeConfiguredNumber() throws Exception {
    var failures = admission.processAll(Workload.SCAN, IntStream.range(0, 2).boxed(), shortItem);

    assertThat(failures).isEmpty();
    assertThat(started).hasValue(2);
  }
}
