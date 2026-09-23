package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.domain.task.RequestedProbeResult;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeProbeTaskRequests;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe runs")
class ProbeRunsTest {

  private static final Duration CHECK_INTERVAL = Duration.ofSeconds(2);

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMediaFileContainerInfoRepository outcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeProbeTaskRequests requests = new FakeProbeTaskRequests(outcomes);
  private final MutableClock clock = new MutableClock();
  private final List<Duration> sleeps = new ArrayList<>();
  private IntConsumer onCheck = _ -> {};
  private FileSystem fileSystem;
  private ProbeRuns probeRuns;

  @BeforeEach
  void setUp() {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    probeRuns =
        ProbeRuns.builder()
            .scheduler(
                MediaFileProbeTaskScheduler.builder()
                    .mediaFileRepository(mediaFiles)
                    .reader(new PersistedProbeReader(outcomes))
                    .probeTaskRequests(requests)
                    .fileSystem(fileSystem)
                    .build())
            .outcomes(outcomes)
            .sleeper(
                duration -> {
                  sleeps.add(duration);
                  clock.advance(duration);
                  onCheck.accept(sleeps.size());
                })
            .clock(clock)
            .properties(new ProbeSchedulingProperties(null, CHECK_INTERVAL))
            .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should wait until the requested probe stores an outcome")
  void shouldWaitUntilTheRequestedProbeStoresAnOutcome() throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    onCheck = _ -> requests.succeed(onlyRequest());

    var summary = probeRuns.awaitResults(run);

    assertThat(summary.count(RequestedProbeResult.READY)).isOne();
    assertThat(sleeps).containsExactly(CHECK_INTERVAL);
  }

  @Test
  @DisplayName("Should count a stored outcome that already matches the source without waiting")
  void shouldCountAStoredOutcomeThatAlreadyMatchesTheSourceWithoutWaiting() throws Exception {
    var mediaFileId = mediaFile("movie.mkv").getId();
    var earlier = probeRuns.open();
    earlier.request(mediaFileId);
    requests.succeed(onlyRequest());
    var run = probeRuns.open();

    run.request(mediaFileId);
    var summary = probeRuns.awaitResults(run);

    assertThat(requests.requests()).hasSize(1);
    assertThat(summary.count(RequestedProbeResult.READY)).isOne();
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("Should stop waiting once the failure of an attempt is recorded")
  void shouldStopWaitingOnceTheFailureOfAnAttemptIsRecorded() throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    onCheck = _ -> requests.fail(onlyRequest(), ItemFailureReason.SOURCE_INACCESSIBLE);

    var summary = probeRuns.awaitResults(run);

    assertThat(summary.count(RequestedProbeResult.FAILED)).isOne();
    assertThat(sleeps).hasSize(1);
  }

  @Test
  @DisplayName("Should keep waiting while no attempt at the requested inputs has finished")
  void shouldKeepWaitingWhileNoAttemptAtTheRequestedInputsHasFinished() throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    onCheck =
        checks -> {
          if (checks == 3) {
            requests.succeed(onlyRequest());
          }
        };

    var summary = probeRuns.awaitResults(run);

    assertThat(summary.count(RequestedProbeResult.READY)).isOne();
    assertThat(sleeps).hasSize(3);
  }

  @Test
  @DisplayName("Should stop waiting for a file whose later change replaced the requested inputs")
  void shouldStopWaitingForAFileWhoseLaterChangeReplacedTheRequestedInputs() throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    onCheck = _ -> requests.request(changed(onlyRequest()));

    var summary = probeRuns.awaitResults(run);

    assertThat(summary.count(RequestedProbeResult.SUPERSEDED)).isOne();
  }

  @Test
  @DisplayName("Should stop waiting for a media file that was removed")
  void shouldStopWaitingForAMediaFileThatWasRemoved() throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    onCheck = _ -> outcomes.mediaFileExistsWhen(_ -> false);

    var summary = probeRuns.awaitResults(run);

    assertThat(summary.count(RequestedProbeResult.REMOVED)).isOne();
  }

  @Test
  @DisplayName("Should not wait for a file whose source is missing when the probe is requested")
  void shouldNotWaitForAFileWhoseSourceIsMissingWhenTheProbeIsRequested() throws Exception {
    var mediaFile = mediaFile("movie.mkv");
    Files.delete(FilepathCodec.decode(fileSystem, mediaFile.getFilepathUri()));
    var run = probeRuns.open();

    run.request(mediaFile.getId());
    var summary = probeRuns.awaitResults(run);

    assertThat(requests.requests()).isEmpty();
    assertThat(summary.total()).isZero();
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("Should wait only for the probes its own run requested")
  void shouldWaitOnlyForTheProbesItsOwnRunRequested() throws Exception {
    var other = probeRuns.open();
    other.request(mediaFile("other.mkv").getId());
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    requests.succeed(requests.requests().getLast());

    var summary = probeRuns.awaitResults(run);

    assertThat(summary.total()).isOne();
    assertThat(sleeps).isEmpty();
  }

  @Test
  @DisplayName("Should time the probes from the first request until the last result")
  void shouldTimeTheProbesFromTheFirstRequestUntilTheLastResult() throws Exception {
    var run = probeRuns.open();
    clock.advance(Duration.ofSeconds(30));
    run.request(mediaFile("movie.mkv").getId());
    onCheck =
        checks -> {
          if (checks == 2) {
            requests.succeed(onlyRequest());
          }
        };

    var summary = probeRuns.awaitResults(run);

    assertThat(summary.elapsed()).isEqualTo(CHECK_INTERVAL.multipliedBy(2));
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

  private ProbeTaskRequest onlyRequest() {
    assertThat(requests.requests()).hasSize(1);
    return requests.requests().getFirst();
  }

  private static ProbeTaskRequest changed(ProbeTaskRequest request) {
    return request.toBuilder()
        .snapshot(
            new SourceFileSnapshot(request.snapshot().size() + 1, request.snapshot().modifiedAt()))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }
}
