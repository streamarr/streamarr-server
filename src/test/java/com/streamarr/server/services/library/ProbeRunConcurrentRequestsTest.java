package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeProbeTaskRequests;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe runs with concurrent requests")
class ProbeRunConcurrentRequestsTest {

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMediaFileContainerInfoRepository outcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeProbeTaskRequests requests = new FakeProbeTaskRequests(outcomes);
  private final MutableClock clock = new MutableClock();
  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());

  @AfterEach
  void tearDown() throws IOException {
    fileSystem.close();
  }

  @Test
  @DisplayName(
      "Should measure elapsed time from the earliest request when a later request finishes scheduling first")
  void shouldMeasureElapsedTimeFromTheEarliestRequestWhenALaterRequestFinishesSchedulingFirst()
      throws Exception {
    var earlierFileId = mediaFile("earlier.mkv").getId();
    var laterFileId = mediaFile("later.mkv").getId();
    requests.succeedEachRequest();
    var parked = new ParkedRequests(requests, earlierFileId);
    var probeRuns = probeRuns(parked);
    var run = probeRuns.open();

    var earlierRequest = CompletableFuture.runAsync(() -> run.request(earlierFileId));
    assertThat(parked.entered.await(5, TimeUnit.SECONDS)).isTrue();
    clock.advance(Duration.ofSeconds(5));
    run.request(laterFileId);
    parked.release.countDown();
    earlierRequest.get(5, TimeUnit.SECONDS);

    var summary = probeRuns.awaitResults(run);

    assertThat(summary.elapsed()).isEqualTo(Duration.ofSeconds(5));
  }

  private ProbeRuns probeRuns(ProbeTaskRequests probeTaskRequests) {
    return ProbeRuns.builder()
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
              throw new AssertionError("every requested probe already has its outcome");
            })
        .clock(clock)
        .properties(new ProbeSchedulingProperties(null, Duration.ofSeconds(2)))
        .build();
  }

  private MediaFile mediaFile(String filename) throws IOException {
    var path = Files.writeString(fileSystem.getPath("/" + filename), "media");
    return mediaFiles.save(
        MediaFile.builder()
            .libraryId(UUID.randomUUID())
            .filename(filename)
            .filepathUri(FilepathCodec.encode(path))
            .status(MediaFileStatus.MATCHED)
            .build());
  }

  /** Holds one media file's request inside scheduling until the test releases it. */
  private static final class ParkedRequests implements ProbeTaskRequests {

    private final ProbeTaskRequests delegate;
    private final UUID parkedMediaFileId;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private ParkedRequests(ProbeTaskRequests delegate, UUID parkedMediaFileId) {
      this.delegate = delegate;
      this.parkedMediaFileId = parkedMediaFileId;
    }

    @Override
    public void request(ProbeTaskRequest request) {
      delegate.request(request);
    }

    @Override
    public void requestRetryingFailure(ProbeTaskRequest request) {
      if (request.mediaFileId().equals(parkedMediaFileId)) {
        entered.countDown();
        awaitRelease();
      }

      delegate.requestRetryingFailure(request);
    }

    private void awaitRelease() {
      try {
        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }
}
