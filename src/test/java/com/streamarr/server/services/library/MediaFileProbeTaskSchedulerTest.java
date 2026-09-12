package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.ProbeTaskSchedulingException;
import com.streamarr.server.fakes.CapturingProbeTaskRequests;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.services.events.library.MediaFileProbeTaskRequested;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.MediaFileProbeTaskScheduler.MediaFileProbeTaskSchedulerBuilder;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Media file probe task scheduling")
class MediaFileProbeTaskSchedulerTest {

  private static final Instant MODIFIED_AT = Instant.parse("2026-09-10T12:00:00.999999999Z");

  private final FakeMediaFileRepository files = new FakeMediaFileRepository();
  private final CapturingProbeTaskRequests requests = new CapturingProbeTaskRequests();
  private FileSystem fileSystem;
  private Path path;
  private MediaFile mediaFile;

  @BeforeEach
  void setUp() throws Exception {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    path = Files.writeString(fileSystem.getPath("/movie.mkv"), "media");
    Files.setLastModifiedTime(path, FileTime.from(MODIFIED_AT));
    mediaFile =
        files.save(
            MediaFile.builder()
                .libraryId(UUID.randomUUID())
                .filepathUri(FilepathCodec.encode(path))
                .status(MediaFileStatus.MATCHED)
                .build());
  }

  @AfterEach
  void tearDown() throws Exception {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should enqueue the observed snapshot when no probe outcome exists")
  void shouldEnqueueObservedSnapshotWhenNoProbeOutcomeExists() {
    var scheduler = schedulerBuilder().build();

    scheduler.onProbeTaskRequested(new MediaFileProbeTaskRequested(mediaFile.getId()));

    assertThat(requests.requests())
        .containsExactly(
            ProbeTaskRequest.builder()
                .mediaFileId(mediaFile.getId())
                .libraryId(mediaFile.getLibraryId())
                .filepathUri(mediaFile.getFilepathUri())
                .snapshot(new SourceFileSnapshot(5, MODIFIED_AT))
                .probeVersion(ProbeVersion.CURRENT)
                .build());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should skip enqueue when the current outcome matches the observed source")
  void shouldSkipEnqueueWhenCurrentOutcomeMatchesObservedSource(boolean terminalFailure) {
    var outcome =
        MediaFileContainerInfo.builder()
            .mediaFileId(mediaFile.getId())
            .snapshot(new SourceFileSnapshot(5, MODIFIED_AT))
            .probeVersion(ProbeVersion.CURRENT)
            .probeError(terminalFailure ? ProbeError.INVALID_MEDIA : null)
            .build();
    var stored = new FakeMediaFileContainerInfoRepository();
    stored.store(outcome);
    var scheduler = schedulerBuilder().reader(new PersistedProbeReader(stored)).build();

    scheduler.onProbeTaskRequested(new MediaFileProbeTaskRequested(mediaFile.getId()));

    assertThat(requests.requests()).isEmpty();
  }

  @Test
  @DisplayName("Should reject scheduling without enqueue when the media row no longer exists")
  void shouldRejectSchedulingWithoutEnqueueWhenMediaRowNoLongerExists() {
    files.deleteById(mediaFile.getId());
    var scheduler = schedulerBuilder().build();
    var event = new MediaFileProbeTaskRequested(mediaFile.getId());

    assertThatThrownBy(() -> scheduler.onProbeTaskRequested(event))
        .isInstanceOf(MediaFileNotFoundException.class);
    assertThat(requests.requests()).isEmpty();
  }

  @Test
  @DisplayName("Should skip enqueue when the source disappears")
  void shouldSkipEnqueueWhenSourceDisappears() throws Exception {
    Files.delete(path);
    var scheduler = schedulerBuilder().build();
    var event = new MediaFileProbeTaskRequested(mediaFile.getId());

    assertThatNoException().isThrownBy(() -> scheduler.onProbeTaskRequested(event));

    assertThat(requests.requests()).isEmpty();
  }

  @Test
  @DisplayName("Should preserve the read failure when the source contains a symbolic-link loop")
  void shouldPreserveReadFailureWhenSourceContainsASymbolicLinkLoop() throws Exception {
    Files.delete(path);
    Files.createSymbolicLink(path, path.getFileName());
    var scheduler = schedulerBuilder().build();
    var event = new MediaFileProbeTaskRequested(mediaFile.getId());

    assertThatThrownBy(() -> scheduler.onProbeTaskRequested(event))
        .isInstanceOf(ProbeTaskSchedulingException.class)
        .hasCauseInstanceOf(IOException.class);
    assertThat(requests.requests()).isEmpty();
  }

  private MediaFileProbeTaskSchedulerBuilder schedulerBuilder() {
    return MediaFileProbeTaskScheduler.builder()
        .mediaFileRepository(files)
        .reader(new PersistedProbeReader(new FakeMediaFileContainerInfoRepository()))
        .probeTaskRequests(requests)
        .fileSystem(fileSystem);
  }
}
