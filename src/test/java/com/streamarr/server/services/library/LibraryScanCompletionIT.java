package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.support.BoundedTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Library scan completion")
class LibraryScanCompletionIT extends AbstractProbeSchedulerIntegrationTest {

  private static final Duration SCAN_BOUND = Duration.ofSeconds(20);

  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryRepository libraries;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private ProbeTaskRequests probeTaskRequests;

  private final List<MediaFile> matchedFiles = new ArrayList<>();
  private final List<Library> scannedLibraries = new ArrayList<>();
  private Library library;
  private MediaFile mediaFile;

  @BeforeEach
  void createMatchedFile() throws IOException {
    library = scannedLibrary(Files.createDirectories(tempDir.resolve("movies")));
    mediaFile = matchedFile(library, "Movie (2024).mkv");
  }

  @AfterEach
  void removeScannedLibraries() {
    mediaFiles.deleteAll(matchedFiles);
    libraries.deleteAll(scannedLibraries);
  }

  @Test
  @DisplayName("Should stay scanning until the probe succeeds when every worker is busy")
  void shouldStayScanningUntilTheProbeSucceedsWhenEveryWorkerIsBusy() throws Exception {
    var busy = new AtomicBoolean(true);
    var deferred = new CountDownLatch(1);
    var producer = new FakeFfprobeService();
    FfprobeService busyUntilReleased =
        request -> {
          if (busy.get()) {
            throw new ProbeWorkersBusyException();
          }

          return producer.probe(request);
        };

    startScheduler(
        probeExecution.toBuilder().producer(busyUntilReleased).build(), countingOk(deferred));

    try (var scan =
        BoundedTask.start(
            () -> {
              libraryManagementService.scanLibrary(library.getId());
              return reader.find(mediaFile.getId()).isPresent();
            })) {
      assertThat(deferred.await(10, TimeUnit.SECONDS)).isTrue();
      busy.set(false);

      assertThat(scan.await(SCAN_BOUND)).as("probe outcome stored when the scan returned").isTrue();
    }

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should finish the scan and keep retrying when a probe failure is recorded")
  void shouldFinishTheScanAndKeepRetryingWhenAProbeFailureIsRecorded() throws Exception {
    var client =
        startScheduler(
            probeExecution.toBuilder()
                .producer(
                    _ -> {
                      throw new ProbeExecutionException(
                          ItemFailureReason.SOURCE_INACCESSIBLE,
                          "Worker could not read the source");
                    })
                .build(),
            new AbstractSchedulerListener() {});

    scan(library);

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(outcomes.findProbeStates(List.of(mediaFile.getId())))
        .singleElement()
        .satisfies(
            state ->
                assertThat(state.failure())
                    .hasValueSatisfying(
                        failure ->
                            assertThat(failure.reason())
                                .isEqualTo(ItemFailureReason.SOURCE_INACCESSIBLE)));
    assertThat(client.getScheduledExecution(instanceOf(mediaFile))).isPresent();
  }

  @Test
  @DisplayName(
      "Should finish the scan when the source becomes unreadable while an attempt at older inputs"
          + " runs")
  void shouldFinishTheScanWhenTheSourceBecomesUnreadableWhileAnAttemptAtOlderInputsRuns()
      throws Exception {
    var probing = new CountDownLatch(1);
    var release = new CompletableFuture<Void>();
    probeTaskRequests.request(request(mediaFile));
    startScheduler(
        probeExecution.toBuilder().producer(workerFailingAfter(probing, release)).build(),
        new AbstractSchedulerListener() {});
    assertThat(probing.await(10, TimeUnit.SECONDS)).isTrue();
    var source = FilepathCodec.decode(mediaFile.getFilepathUri());
    Files.writeString(source, "changed media");

    try (var scan =
        BoundedTask.start(() -> libraryManagementService.scanLibrary(library.getId()))) {
      var changed = snapshotOf(mediaFile);
      await().atMost(Duration.ofSeconds(10)).until(() -> isRequestedAt(mediaFile, changed));
      makeUnreadable(source);
      release.complete(null);

      scan.await(SCAN_BOUND);
    }

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should probe a failed file again and wait for it when the library is rescanned")
  void shouldProbeAFailedFileAgainAndWaitForItWhenTheLibraryIsRescanned() throws Exception {
    var readable = new AtomicBoolean(false);
    var producer = new FakeFfprobeService();
    startScheduler(
        probeExecution.toBuilder()
            .producer(
                request -> {
                  if (!readable.get()) {
                    throw new ProbeExecutionException(
                        ItemFailureReason.SOURCE_INACCESSIBLE, "Worker could not read the source");
                  }

                  return producer.probe(request);
                })
            .build(),
        new AbstractSchedulerListener() {});
    scan(library);
    assertThat(reader.find(mediaFile.getId())).isEmpty();

    readable.set(true);
    scan(library);

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(reader.find(mediaFile.getId())).isPresent();
  }

  @Test
  @DisplayName("Should finish the scan when a requested source is removed before it is probed")
  void shouldFinishTheScanWhenARequestedSourceIsRemovedBeforeItIsProbed() throws Exception {
    try (var scan =
        BoundedTask.start(() -> libraryManagementService.scanLibrary(library.getId()))) {
      await().atMost(Duration.ofSeconds(10)).until(() -> isScheduled(mediaFile));
      Files.delete(FilepathCodec.decode(mediaFile.getFilepathUri()));

      startScheduler(probeExecution, new AbstractSchedulerListener() {});
      scan.await(SCAN_BOUND);
    }

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName(
      "Should finish the scan when the source is removed during an attempt at replaced inputs")
  void shouldFinishTheScanWhenTheSourceIsRemovedDuringAnAttemptAtReplacedInputs() throws Exception {
    var probing = new CountDownLatch(1);
    var release = new CompletableFuture<Void>();
    var producer = new FakeFfprobeService();
    probeTaskRequests.request(request(mediaFile));
    startScheduler(
        probeExecution.toBuilder()
            .producer(
                request -> {
                  probing.countDown();
                  release.join();
                  return producer.probe(request);
                })
            .build(),
        new AbstractSchedulerListener() {});
    assertThat(probing.await(10, TimeUnit.SECONDS)).isTrue();
    var source = FilepathCodec.decode(mediaFile.getFilepathUri());
    Files.writeString(source, "changed media");

    try (var scan =
        BoundedTask.start(() -> libraryManagementService.scanLibrary(library.getId()))) {
      var changed = snapshotOf(mediaFile);
      await().atMost(Duration.ofSeconds(10)).until(() -> isRequestedAt(mediaFile, changed));
      Files.delete(source);
      release.complete(null);

      scan.await(SCAN_BOUND);
    }

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should finish the scan when another library has pending probes")
  void shouldFinishTheScanWhenAnotherLibraryHasPendingProbes() throws Exception {
    var otherLibrary = scannedLibrary(Files.createDirectories(tempDir.resolve("other")));
    probeTaskRequests.request(request(matchedFile(otherLibrary, "Other (2024).mkv")));
    outcomes.publish(
        ProbePublication.builder()
            .mediaFileId(mediaFile.getId())
            .snapshot(snapshotOf(mediaFile))
            .probeVersion(ProbeVersion.CURRENT)
            .outcome(new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA))
            .build());

    scan(library);

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
  }

  @Test
  @DisplayName("Should finish the scan when a newer probe version already stored the outcome")
  void shouldFinishTheScanWhenANewerProbeVersionAlreadyStoredTheOutcome() throws Exception {
    outcomes.publish(
        ProbePublication.builder()
            .mediaFileId(mediaFile.getId())
            .snapshot(snapshotOf(mediaFile))
            .probeVersion(ProbeVersion.CURRENT + 1)
            .outcome(new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA))
            .build());

    scan(library);

    assertThat(statusOf(library)).isEqualTo(LibraryStatus.HEALTHY);
  }

  private void scan(Library scanned) throws Exception {
    BoundedTask.runWithin(SCAN_BOUND, () -> libraryManagementService.scanLibrary(scanned.getId()));
  }

  private Library scannedLibrary(Path root) {
    var saved =
        libraries.saveAndFlush(
            LibraryFixtureCreator.unsavedLibraryBuilder()
                .name("Scan completion " + root.getFileName())
                .filepathUri(FilepathCodec.encode(root))
                .status(LibraryStatus.HEALTHY)
                .build());
    scannedLibraries.add(saved);
    return saved;
  }

  private MediaFile matchedFile(Library owner, String filename) throws IOException {
    var root = FilepathCodec.decode(owner.getFilepathUri());
    var source = Files.writeString(root.resolve(filename), "media");
    var saved =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(owner.getId())
                .filepathUri(FilepathCodec.encode(source))
                .filename(filename)
                .size(Files.size(source))
                .status(MediaFileStatus.MATCHED)
                .build());
    matchedFiles.add(saved);
    return saved;
  }

  // Tests poll this and isRequestedAt: a scan saves its probe requests inside the service, which
  // gives a test no hook to wait on.
  private boolean isScheduled(MediaFile file) {
    return outcomes.findProbeStates(List.of(file.getId())).stream()
        .anyMatch(state -> state.requested().isPresent());
  }

  private boolean isRequestedAt(MediaFile file, SourceFileSnapshot snapshot) {
    return outcomes.findProbeStates(List.of(file.getId())).stream()
        .anyMatch(
            state ->
                state.requested().filter(inputs -> inputs.snapshot().equals(snapshot)).isPresent());
  }

  private LibraryStatus statusOf(Library scanned) {
    return libraries.findById(scanned.getId()).orElseThrow().getStatus();
  }

  private static AbstractSchedulerListener countingOk(CountDownLatch completions) {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete executionComplete) {
        if (executionComplete.getResult() == ExecutionComplete.Result.OK) {
          completions.countDown();
        }
      }
    };
  }

  private static TaskInstanceId instanceOf(MediaFile file) {
    return TaskInstanceId.of(MediaProbeTask.NAME, file.getId().toString());
  }

  private static ProbeTaskRequest request(MediaFile file) throws IOException {
    return ProbeTaskRequest.builder()
        .mediaFileId(file.getId())
        .libraryId(file.getLibraryId())
        .filepathUri(file.getFilepathUri())
        .snapshot(snapshotOf(file))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }

  private static SourceFileSnapshot snapshotOf(MediaFile file) throws IOException {
    var attributes =
        Files.readAttributes(
            FilepathCodec.decode(file.getFilepathUri()), BasicFileAttributes.class);
    return new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant());
  }
}
