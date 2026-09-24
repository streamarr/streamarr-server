package com.streamarr.server.fixtures;

import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeProbeTaskRequests;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.library.FileDiscoveryRuns;
import com.streamarr.server.services.library.MediaFileProbeTaskScheduler;
import com.streamarr.server.services.library.ProbeRuns;
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
   * Probe results are checked again without waiting in real time. A check yields to other virtual
   * threads, and a cancelled wait stops at its next check.
   */
  @Builder(builderMethodName = "fileDiscoveryRunsBuilder")
  private static FileDiscoveryRuns fileDiscoveryRuns(
      @NonNull ArtworkService artworkService,
      @NonNull MediaFileRepository mediaFiles,
      @NonNull FakeMediaFileContainerInfoRepository outcomes,
      @NonNull FakeProbeTaskRequests probeTaskRequests,
      @NonNull FileSystem fileSystem) {
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
                .sleeper(
                    _ -> {
                      if (Thread.interrupted()) {
                        throw new InterruptedException();
                      }

                      Thread.yield();
                    })
                .clock(Clock.systemUTC())
                .properties(new ProbeSchedulingProperties(null, CHECK_INTERVAL))
                .build())
        .mediaFiles(mediaFiles)
        .build();
  }
}
