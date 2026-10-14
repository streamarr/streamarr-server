package com.streamarr.server.fixtures;

import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeProbeTaskRequests;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.library.FileDiscoveryRuns;
import com.streamarr.server.services.library.MediaFileProbeTaskScheduler;
import com.streamarr.server.services.library.ProbeRuns;
import com.streamarr.server.services.library.Sleeper;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.nio.file.FileSystem;
import java.time.Clock;
import java.time.Duration;
import lombok.Builder;
import lombok.NonNull;

public final class FileDiscoveryRunsFixture {

  private static final Duration CHECK_INTERVAL = Duration.ofMillis(10);

  private FileDiscoveryRunsFixture() {}

  /**
   * Unless a test sets a sleeper, probe results are checked again without waiting in real time: a
   * check yields to other virtual threads, and a cancelled wait stops at its next check. The clock
   * defaults to the system clock.
   */
  @Builder(builderMethodName = "fileDiscoveryRunsBuilder")
  private static FileDiscoveryRuns fileDiscoveryRuns(
      @NonNull ArtworkService artworkService,
      @NonNull MediaFileRepository mediaFiles,
      @NonNull FakeMediaFileContainerInfoRepository outcomes,
      @NonNull FakeProbeTaskRequests probeTaskRequests,
      @NonNull FileSystem fileSystem,
      Sleeper sleeper,
      Clock clock) {
    var probeSleeper = sleeper;
    if (probeSleeper == null) {
      probeSleeper = FileDiscoveryRunsFixture::yieldUntilInterrupted;
    }

    var probeClock = clock;
    if (probeClock == null) {
      probeClock = Clock.systemUTC();
    }

    return FileDiscoveryRuns.builder()
        .artworkService(artworkService)
        .probeRuns(
            ProbeRuns.builder()
                .scheduler(
                    MediaFileProbeTaskScheduler.builder()
                        .mediaFileRepository(mediaFiles)
                        .reader(new PersistedProbeReader(outcomes))
                        .probeTaskRequests(probeTaskRequests)
                        .fileSystem(fileSystem)
                        .build())
                .outcomes(outcomes)
                .sleeper(probeSleeper)
                .clock(probeClock)
                .properties(new ProbeSchedulingProperties(null, CHECK_INTERVAL))
                .build())
        .mediaFiles(mediaFiles)
        .build();
  }

  private static void yieldUntilInterrupted(Duration ignored) throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    Thread.yield();
  }
}
