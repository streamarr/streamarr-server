package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.AdmissionEndFixture.neverReached;
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
import com.streamarr.server.fakes.CapturingKeyframePacketScanRequests;
import com.streamarr.server.fakes.CountingSleeper;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeProbeTaskRequests;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.ProbeCapacity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Probe runs")
class ProbeRunsTest {

  private static final Duration CHECK_INTERVAL = Duration.ofSeconds(2);
  private static final String RUN_DESCRIPTION = "scan of library 'Probes'";

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMediaFileContainerInfoRepository outcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeProbeTaskRequests requests = new FakeProbeTaskRequests(outcomes);
  private final MutableClock clock = new MutableClock();
  private final CountingSleeper sleeper = new CountingSleeper(clock);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final AtomicReference<ProbeCapacity> capacity =
      new AtomicReference<>(ProbeCapacity.builder().build());
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
                    .packetScans(new CapturingKeyframePacketScanRequests())
                    .fileSystem(fileSystem)
                    .build())
            .outcomes(outcomes)
            .sleeper(sleeper)
            .clock(clock)
            .properties(new ProbeSchedulingProperties(null, CHECK_INTERVAL, null))
            .capacitySource(capacity::get)
            .scanProbesWaiting(new ScanProbesWaiting(meterRegistry))
            .build();
  }

  private double waitingGauge() {
    return meterRegistry.get("streamarr.probes.waiting").gauge().value();
  }

  @AfterEach
  void tearDown() throws IOException {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should stop waiting when the requested probe stores an outcome")
  void shouldStopWaitingWhenTheRequestedProbeStoresAnOutcome() throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    sleeper.onSleep(_ -> requests.succeed(onlyRequest()));

    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

    assertThat(summary.count(RequestedProbeResult.READY)).isOne();
    assertThat(sleeper.sleeps()).containsExactly(CHECK_INTERVAL);
  }

  @Test
  @DisplayName(
      "Should report the probes that the scan still waits for until the scan stops waiting when a"
          + " scan waits for its probes")
  void shouldReportTheProbesThatTheScanStillWaitsForUntilItStopsWaitingWhenAScanWaits()
      throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("first.mkv").getId());
    run.request(mediaFile("second.mkv").getId());
    var waitingDuringSleep = new AtomicReference<Double>();
    var reached = new AtomicBoolean();
    sleeper.onSleep(
        _ -> {
          waitingDuringSleep.set(waitingGauge());
          reached.set(true);
        });

    probeRuns.awaitResults(run, RUN_DESCRIPTION, reached::get);

    assertThat(waitingDuringSleep.get()).isEqualTo(2);
    assertThat(waitingGauge()).isZero();
  }

  @ParameterizedTest(name = "background probe capacity {0}")
  @ValueSource(ints = {0, 4})
  @DisplayName(
      "Should keep waiting for the probe when the probe outlasts the progress report interval")
  void shouldKeepWaitingForTheProbeWhenTheProbeOutlastsTheProgressReportInterval(
      int backgroundProbeCapacity) throws Exception {
    capacity.set(ProbeCapacity.builder().backgroundProbeCapacity(backgroundProbeCapacity).build());
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    sleeper.onSleep(
        sleeps -> {
          if (sleeps == 20) {
            requests.succeed(onlyRequest());
          }
        });

    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

    assertThat(summary.count(RequestedProbeResult.READY)).isOne();
    assertThat(sleeper.sleeps()).hasSize(20);
  }

  @Test
  @DisplayName("Should stop waiting for a requested probe when the admission end is reached")
  void shouldStopWaitingForARequestedProbeWhenTheAdmissionEndIsReached() throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    var reached = new AtomicBoolean();
    sleeper.onSleep(_ -> reached.set(true));

    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, reached::get);

    assertThat(summary.count(RequestedProbeResult.READY)).isZero();
    assertThat(sleeper.sleeps()).containsExactly(CHECK_INTERVAL);
  }

  @Test
  @DisplayName(
      "Should count the probe without waiting when a stored outcome already matches the source")
  void shouldCountTheProbeWithoutWaitingWhenAStoredOutcomeAlreadyMatchesTheSource()
      throws Exception {
    var mediaFileId = mediaFile("movie.mkv").getId();
    var earlier = probeRuns.open();
    earlier.request(mediaFileId);
    requests.succeed(onlyRequest());
    var run = probeRuns.open();

    run.request(mediaFileId);
    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

    assertThat(requests.requests()).hasSize(1);
    assertThat(summary.count(RequestedProbeResult.READY)).isOne();
    assertThat(sleeper.sleeps()).isEmpty();
  }

  @Test
  @DisplayName("Should stop waiting when an attempt failure is recorded")
  void shouldStopWaitingWhenAnAttemptFailureIsRecorded() throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    sleeper.onSleep(
        _ -> requests.fail(onlyRequest(), ItemFailureReason.SOURCE_INACCESSIBLE, clock.instant()));

    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

    assertThat(summary.count(RequestedProbeResult.FAILED)).isOne();
    assertThat(sleeper.sleeps()).hasSize(1);
  }

  @Test
  @DisplayName("Should wait for a fresh attempt when the failure was recorded before the request")
  void shouldWaitForAFreshAttemptWhenTheFailureWasRecordedBeforeTheRequest() throws Exception {
    var mediaFileId = mediaFile("movie.mkv").getId();
    probeRuns.open().request(mediaFileId);
    requests.fail(onlyRequest(), ItemFailureReason.SOURCE_INACCESSIBLE, clock.instant());
    clock.advance(Duration.ofSeconds(1));
    var run = probeRuns.open();
    run.request(mediaFileId);
    sleeper.onSleep(_ -> requests.succeed(requests.requests().getLast()));

    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

    assertThat(summary.count(RequestedProbeResult.READY)).isOne();
    assertThat(sleeper.sleeps()).hasSize(1);
  }

  @Test
  @DisplayName("Should keep waiting when no attempt at the requested inputs has finished")
  void shouldKeepWaitingWhenNoAttemptAtTheRequestedInputsHasFinished() throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    sleeper.onSleep(
        checks -> {
          if (checks == 3) {
            requests.succeed(onlyRequest());
          }
        });

    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

    assertThat(summary.count(RequestedProbeResult.READY)).isOne();
    assertThat(sleeper.sleeps()).hasSize(3);
  }

  @Test
  @DisplayName("Should stop waiting for a file when a later change replaced the requested inputs")
  void shouldStopWaitingForAFileWhenALaterChangeReplacedTheRequestedInputs() throws Exception {
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    sleeper.onSleep(_ -> requests.request(changed(onlyRequest())));

    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

    assertThat(summary.count(RequestedProbeResult.SUPERSEDED)).isOne();
  }

  @Test
  @DisplayName("Should stop waiting for a media file when it is removed")
  void shouldStopWaitingForAMediaFileWhenItIsRemoved() throws Exception {
    var run = probeRuns.open();
    var mediaFileId = mediaFile("movie.mkv").getId();
    run.request(mediaFileId);
    sleeper.onSleep(_ -> outcomes.deleteMediaFile(mediaFileId));

    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

    assertThat(summary.count(RequestedProbeResult.REMOVED)).isOne();
  }

  @Test
  @DisplayName("Should not wait for a file whose source is missing when the probe is requested")
  void shouldNotWaitForAFileWhoseSourceIsMissingWhenTheProbeIsRequested() throws Exception {
    var mediaFile = mediaFile("movie.mkv");
    Files.delete(FilepathCodec.decode(fileSystem, mediaFile.getFilepathUri()));
    var run = probeRuns.open();

    run.request(mediaFile.getId());
    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

    assertThat(requests.requests()).isEmpty();
    assertThat(summary.counts()).isEmpty();
    assertThat(sleeper.sleeps()).isEmpty();
  }

  @Test
  @DisplayName("Should ignore probes when another run requested them")
  void shouldIgnoreProbesWhenAnotherRunRequestedThem() throws Exception {
    var other = probeRuns.open();
    other.request(mediaFile("other.mkv").getId());
    var run = probeRuns.open();
    run.request(mediaFile("movie.mkv").getId());
    requests.succeed(requests.requests().getLast());

    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

    assertThat(summary.counts()).isEqualTo(Map.of(RequestedProbeResult.READY, 1));
    assertThat(sleeper.sleeps()).isEmpty();
  }

  @Test
  @DisplayName("Should stop the probe timer when the last result arrives")
  void shouldStopTheProbeTimerWhenTheLastResultArrives() throws Exception {
    var run = probeRuns.open();
    clock.advance(Duration.ofSeconds(30));
    run.request(mediaFile("movie.mkv").getId());
    sleeper.onSleep(
        checks -> {
          if (checks == 2) {
            requests.succeed(onlyRequest());
          }
        });

    var summary = probeRuns.awaitResults(run, RUN_DESCRIPTION, neverReached());

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
