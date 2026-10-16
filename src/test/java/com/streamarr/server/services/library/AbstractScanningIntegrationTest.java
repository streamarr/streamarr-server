package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.streamarr.server.AbstractWireMockIntegrationTest;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

abstract class AbstractScanningIntegrationTest extends AbstractWireMockIntegrationTest {

  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean FfprobeService ffprobeService;
  @TestBean SegmentStore segmentStore;

  @Autowired private ApplicationContext scanningContext;

  private ProbeSchedulerStarter.StartedProbeScheduler probeScheduler;

  static TranscodeExecutor transcodeExecutor() {
    return new FakeTranscodeExecutor();
  }

  static FfprobeService ffprobeService() {
    return new FakeFfprobeService();
  }

  static SegmentStore segmentStore() {
    return new FakeSegmentStore();
  }

  @AfterEach
  void stopStartedProbeScheduler() {
    if (probeScheduler != null) {
      probeScheduler.scheduler().stop();
    }
  }

  // Probes run in db-scheduler, and a scan finishes only after its probes have stored results.
  protected void startProbeScheduler() {
    scanningContext.getBean(JdbcTemplate.class).update("DELETE FROM scheduled_tasks");
    probeScheduler =
        ProbeSchedulerStarter.fromContext(scanningContext)
            .start(
                scanningContext.getBean(ProbeExecution.class),
                new AbstractSchedulerListener() {},
                Clock.systemUTC());
  }
}
