package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.domain.task.FileProcessingTaskStatus;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.task.FileProcessingTaskRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.LegacyProbeTaskRecovery.LegacyProbeTaskRecoveryBuilder;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import com.streamarr.server.services.validation.VideoExtensionValidator;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Legacy probe task recovery")
class LegacyProbeTaskRecoveryIT extends AbstractIntegrationTest {

  @TempDir Path directory;

  @Autowired private LibraryRepository libraries;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private FileProcessingTaskRepository tasks;
  @Autowired private FileProcessingTaskCoordinator coordinator;
  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private JdbcTemplate jdbc;

  @Qualifier("probeSchedulerClient")
  @Autowired
  private SchedulerClient client;

  private LegacyProbeTaskRecovery recovery;

  @BeforeEach
  void clearTasks() {
    tasks.deleteAll();
    jdbc.update("DELETE FROM scheduled_tasks");
    recovery = recoveryBuilder().build();
  }

  @AfterEach
  void closeRecovery() {
    recovery.close();
  }

  @Test
  @DisplayName("Should adopt the existing task when recovering a matched media file")
  void shouldAdoptExistingTaskWhenRecoveringMatchedMediaFile() throws Exception {
    var path = Files.writeString(directory.resolve("Movie (2024).mkv"), "media");
    var library = createLibrary();
    var file =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri(FilepathCodec.encode(path))
                .filename(path.getFileName().toString())
                .size(5)
                .status(MediaFileStatus.MATCHED)
                .build());
    var legacy = createTask(path, library);

    recovery.recover();

    assertThat(tasks.findById(legacy.getId()).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.COMPLETED);
    assertThat(scheduledRequest(file))
        .hasValueSatisfying(
            request -> {
              assertThat(request.mediaFileId()).isEqualTo(file.getId());
              assertThat(request.probeVersion()).isEqualTo(ProbeVersion.CURRENT);
            });
  }

  private enum InvalidSource {
    MISSING,
    DIRECTORY,
    UNSUPPORTED
  }

  @Test
  @DisplayName("Should recover all legacy tasks when the backlog exceeds one batch")
  void shouldRecoverAllLegacyTasksWhenBacklogExceedsOneBatch() throws Exception {
    var library = createLibrary();
    for (var index = 0; index < 101; index++) {
      createTask(directory.resolve("Missing-" + index + ".mkv"), library);
    }

    recovery.recover();

    assertThat(tasks.findAll())
        .hasSize(101)
        .allSatisfy(
            task -> assertThat(task.getStatus()).isEqualTo(FileProcessingTaskStatus.FAILED));
  }

  @Test
  @DisplayName("Should retain a temporarily inaccessible task and continue recovering other files")
  void shouldRetainTemporarilyInaccessibleTaskAndContinueRecoveringOtherFiles() throws Exception {
    var library = createLibrary();
    createTask(Files.writeString(directory.resolve("First.txt"), "notes"), library);
    createTask(Files.writeString(directory.resolve("Second.txt"), "notes"), library);
    var pending = coordinator.findLegacyTasks(Optional.empty(), 100);
    var denied = pending.getFirst();
    var deniedPath = FilepathCodec.decode(denied.getFilepathUri());

    try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files
          .when(() -> Files.readAttributes(deniedPath, BasicFileAttributes.class))
          .thenThrow(new AccessDeniedException(deniedPath.toString()));

      recovery.recover();
    }

    assertThat(tasks.findById(denied.getId()).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.PENDING);
    assertThat(tasks.findById(pending.getLast().getId()).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.FAILED);

    recovery.recover();

    assertThat(tasks.findById(denied.getId()).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.FAILED);
  }

  @Test
  @DisplayName("Should complete the legacy task when its unchanged probe outcome already exists")
  void shouldCompleteLegacyTaskWhenUnchangedProbeOutcomeAlreadyExists() throws Exception {
    var path = Files.writeString(directory.resolve("Current (2024).mkv"), "media");
    var library = createLibrary();
    var file =
        mediaFiles.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .filepathUri(FilepathCodec.encode(path))
                .filename(path.getFileName().toString())
                .size(5)
                .status(MediaFileStatus.MATCHED)
                .build());
    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
    var snapshot =
        new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant());
    assertThat(
            outcomes.publish(
                ProbePublication.builder()
                    .mediaFileId(file.getId())
                    .snapshot(snapshot)
                    .probeVersion(ProbeVersion.CURRENT)
                    .outcome(new ProbeOutcome.Failure(ProbeError.NO_VIDEO_STREAM))
                    .build()))
        .isTrue();
    var legacy = createTask(path, library);

    recovery.recover();

    assertThat(tasks.findById(legacy.getId()).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.COMPLETED);
    assertThat(scheduledRequest(file)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(InvalidSource.class)
  @DisplayName("Should retire the legacy task when its source is missing or unsupported")
  void shouldRetireLegacyTaskWhenSourceIsMissingOrUnsupported(InvalidSource source)
      throws Exception {
    var path =
        switch (source) {
          case MISSING -> directory.resolve("Missing.mkv");
          case DIRECTORY -> Files.createDirectory(directory.resolve("Folder.mkv"));
          case UNSUPPORTED -> Files.writeString(directory.resolve("readme.txt"), "notes");
        };
    var legacy = createTask(path, createLibrary());

    recovery.recover();

    assertThat(tasks.findById(legacy.getId()).orElseThrow().getStatus())
        .isEqualTo(FileProcessingTaskStatus.FAILED);
  }

  private Optional<ProbeRequest> scheduledRequest(MediaFile file) {
    return client
        .getScheduledExecution(TaskInstanceId.of(MediaProbeTask.NAME, file.getId().toString()))
        .map(execution -> (ProbeRequest) execution.getData());
  }

  private Library createLibrary() {
    return libraries.saveAndFlush(
        Library.builder()
            .name("Legacy probe recovery")
            .filepathUri(FilepathCodec.encode(directory))
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .type(MediaType.MOVIE)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build());
  }

  private FileProcessingTask createTask(Path path, Library library) {
    return tasks.saveAndFlush(
        FileProcessingTask.builder()
            .libraryId(library.getId())
            .filepathUri(FilepathCodec.encode(path))
            .status(FileProcessingTaskStatus.PENDING)
            .createdOn(Instant.now())
            .build());
  }

  private LegacyProbeTaskRecoveryBuilder recoveryBuilder() {
    return LegacyProbeTaskRecovery.builder()
        .coordinator(coordinator)
        .libraryManagementService(libraryManagementService)
        .fileSystem(FileSystems.getDefault())
        .extensionValidator(new VideoExtensionValidator());
  }
}
