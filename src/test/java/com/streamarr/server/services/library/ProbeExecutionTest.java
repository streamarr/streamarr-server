package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Probe execution")
class ProbeExecutionTest {

  @TempDir Path tempDir;

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMediaFileContainerInfoRepository outcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeFfprobeService producer = new FakeFfprobeService();
  private Path source;
  private MediaFile mediaFile;

  @BeforeEach
  void setUp() throws IOException {
    source = tempDir.resolve("movie.mkv");
    Files.write(source, new byte[] {1, 2, 3});
    mediaFile =
        mediaFiles.save(
            MediaFile.builder()
                .libraryId(UUID.randomUUID())
                .status(MediaFileStatus.MATCHED)
                .filename("movie.mkv")
                .filepathUri(FilepathCodec.encode(source))
                .size(3)
                .build());
  }

  @Test
  @DisplayName("Should publish the outcome and complete when the source is unchanged")
  void shouldPublishTheOutcomeAndCompleteWhenTheSourceIsUnchanged() {
    var request = request(ProbeVersion.CURRENT);

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(outcomes.publications())
        .singleElement()
        .satisfies(
            publication -> {
              assertThat(publication.mediaFileId()).isEqualTo(mediaFile.getId());
              assertThat(publication.snapshot()).isEqualTo(request.snapshot());
              assertThat(publication.probeVersion()).isEqualTo(ProbeVersion.CURRENT);
              assertThat(publication.outcome()).isEqualTo(producer.probe(source));
            });
  }

  @Test
  @DisplayName("Should complete without probing when a matching outcome is already stored")
  void shouldCompleteWithoutProbingWhenAMatchingOutcomeIsAlreadyStored() {
    var request = request(ProbeVersion.CURRENT);
    outcomes.store(
        MediaFileContainerInfo.builder()
            .mediaFileId(mediaFile.getId())
            .snapshot(request.snapshot())
            .probeVersion(ProbeVersion.CURRENT)
            .container(ProbeContainer.builder().build())
            .build());

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(producer.probeCount()).isZero();
    assertThat(outcomes.publications()).isEmpty();
  }

  @Test
  @DisplayName("Should complete without probing when the media file no longer exists")
  void shouldCompleteWithoutProbingWhenTheMediaFileNoLongerExists() {
    var request = request(ProbeVersion.CURRENT).toBuilder().mediaFileId(UUID.randomUUID()).build();

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName("Should complete without probing when the source file has vanished")
  void shouldCompleteWithoutProbingWhenTheSourceFileHasVanished() throws IOException {
    var request = request(ProbeVersion.CURRENT);
    Files.delete(source);

    var result = execution().execute(request);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName("Should complete without probing when the requested version is newer than supported")
  void shouldCompleteWithoutProbingWhenTheRequestedVersionIsNewerThanSupported() {
    var result = execution().execute(request(ProbeVersion.CURRENT + 1));

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName("Should fail transiently when the source does not stabilize")
  void shouldFailTransientlyWhenTheSourceDoesNotStabilize() {
    var execution = execution().toBuilder().stabilityChecker(_ -> false).build();
    var request = request(ProbeVersion.CURRENT);

    assertThatThrownBy(() -> execution.execute(request))
        .isInstanceOf(ProbeExecutionException.class);
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName(
      "Should reschedule with the observed snapshot when the source changed before probing")
  void shouldRescheduleWithTheObservedSnapshotWhenTheSourceChangedBeforeProbing()
      throws IOException {
    var request = request(ProbeVersion.CURRENT);
    Files.write(source, new byte[] {4, 5}, StandardOpenOption.APPEND);

    var result = execution().execute(request);

    assertThat(result)
        .isEqualTo(
            new ProbeExecutionResult.Rescheduled(
                request.toBuilder().snapshot(snapshot(source)).build()));
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName("Should reschedule at the current version when the request is older")
  void shouldRescheduleAtTheCurrentVersionWhenTheRequestIsOlder() {
    var request = request(ProbeVersion.CURRENT - 1);

    var result = execution().execute(request);

    assertThat(result)
        .isEqualTo(
            new ProbeExecutionResult.Rescheduled(
                request.toBuilder().probeVersion(ProbeVersion.CURRENT).build()));
    assertThat(producer.probeCount()).isZero();
  }

  @Test
  @DisplayName("Should reschedule without publishing when the source changed during the probe")
  void shouldRescheduleWithoutPublishingWhenTheSourceChangedDuringTheProbe() {
    var request = request(ProbeVersion.CURRENT);
    producer.runDuringProbe(() -> append(source));

    var result = execution().execute(request);

    assertThat(result)
        .isEqualTo(
            new ProbeExecutionResult.Rescheduled(
                request.toBuilder().snapshot(snapshot(source)).build()));
    assertThat(outcomes.publications()).isEmpty();
  }

  @Test
  @DisplayName("Should propagate failure without publishing when the producer fails transiently")
  void shouldPropagateFailureWithoutPublishingWhenTheProducerFailsTransiently() {
    producer.failWith(new ProbeExecutionException("worker unavailable"));
    var execution = execution();
    var request = request(ProbeVersion.CURRENT);

    assertThatThrownBy(() -> execution.execute(request))
        .isInstanceOf(ProbeExecutionException.class);
    assertThat(outcomes.publications()).isEmpty();
  }

  private ProbeExecution execution() {
    return ProbeExecution.builder()
        .mediaFiles(mediaFiles)
        .reader(new PersistedProbeReader(outcomes))
        .producer(producer)
        .stabilityChecker(_ -> true)
        .fileSystem(FileSystems.getDefault())
        .outcomes(outcomes)
        .build();
  }

  private ProbeTaskRequest request(int probeVersion) {
    return ProbeTaskRequest.builder()
        .mediaFileId(mediaFile.getId())
        .libraryId(mediaFile.getLibraryId())
        .filepathUri(mediaFile.getFilepathUri())
        .snapshot(snapshot(source))
        .probeVersion(probeVersion)
        .build();
  }

  private static SourceFileSnapshot snapshot(Path path) {
    try {
      var attributes = Files.readAttributes(path, BasicFileAttributes.class);
      return new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant());
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static void append(Path path) {
    try {
      Files.write(path, new byte[] {9}, StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
