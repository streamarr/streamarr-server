package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.LibraryWatcherProperties;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.VirtualTimeSleeper;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.ControlledQuietPeriodConfiguration;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

@Tag("IntegrationTest")
@DisplayName("Probe quiet periods under controlled time")
@Import(ControlledQuietPeriodConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulerProbeQuietPeriodIT extends AbstractProbeSchedulerIntegrationTest {

  @Autowired private VirtualTimeSleeper quietPeriodSleeper;
  @Autowired private FileStabilityChecker fileStabilityChecker;
  @Autowired private LibraryWatcherProperties watcherProperties;

  @Test
  @DisplayName("Should spend no quiet period when a batch of unchanged files is probed")
  void shouldSpendNoQuietPeriodWhenABatchOfUnchangedFilesIsProbed() throws Exception {
    var requests = requestUnchangedFiles(6);
    var allExecutionsFinished = new CountDownLatch(requests.size());
    var waitedBefore = quietPeriodSleeper.totalSlept();

    startScheduler(
        probeExecution.toBuilder().producer(new FakeFfprobeService()).build(),
        countingCompletions(allExecutionsFinished));

    assertThat(allExecutionsFinished.await(15, TimeUnit.SECONDS)).isTrue();
    assertPublished(requests);
    assertThat(quietPeriodSleeper.totalSlept().minus(waitedBefore))
        .as("Virtual quiet-period time spent probing %d unchanged files", requests.size())
        .isZero();
  }

  @Test
  @DisplayName("Should spend one quiet period when the watcher waits for an unchanged file")
  void shouldSpendOneQuietPeriodWhenTheWatcherWaitsForAnUnchangedFile() throws Exception {
    var source = FilepathCodec.decode(requestUnchangedFiles(1).getFirst().filepathUri());
    var waitedBefore = quietPeriodSleeper.totalSlept();

    assertThat(fileStabilityChecker.waitForStability(source)).isTrue();

    assertThat(quietPeriodSleeper.totalSlept().minus(waitedBefore))
        .isEqualTo(Duration.ofSeconds(watcherProperties.stabilizationPeriodSeconds()));
  }
}
