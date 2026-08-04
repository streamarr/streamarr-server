package com.streamarr.server.services.filepath;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import db.migration.V049__Derive_Media_File_Filename_From_Filepath_Uri;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@Isolated
@DisplayName("Media File Filename Migration Integration Tests")
class MediaFileFilenameMigrationIT extends AbstractIntegrationTest {

  @Autowired private DataSource dataSource;
  @Autowired private EntityManager entityManager;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;

  private UUID mediaFileId;
  private UUID additionalMediaFileId;
  private UUID libraryId;

  @AfterEach
  void cleanUp() {
    if (mediaFileId == null) {
      return;
    }

    if (additionalMediaFileId != null) {
      mediaFileRepository.deleteById(additionalMediaFileId);
    }
    mediaFileRepository.deleteById(mediaFileId);
    libraryRepository.deleteById(libraryId);
  }

  @Test
  @DisplayName("Should derive existing media filename from filepath URI when migration runs")
  void shouldDeriveExistingMediaFilenameFromFilepathUriWhenMigrationRuns() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId = library.getId();
    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .filepathUri("file:///media/D%C3%A9j%C3%A0%20Vu%20(2006).mkv")
                .filename("D��j�� Vu (2006).mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    mediaFileId = mediaFile.getId();

    try (var connection = dataSource.getConnection()) {
      new V049__Derive_Media_File_Filename_From_Filepath_Uri()
          .migrate(new MigrationContext(connection));
    }
    entityManager.clear();

    assertThat(mediaFileRepository.findById(mediaFileId).orElseThrow().getFilename())
        .isEqualTo("Déjà Vu (2006).mkv");
  }

  @Test
  @DisplayName(
      "Should preserve audit timestamp when existing filename already matches filepath URI")
  void shouldPreserveAuditTimestampWhenExistingFilenameAlreadyMatchesFilepathUri()
      throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId = library.getId();
    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .filepathUri("file:///media/Am%C3%A9lie%20(2001).mkv")
                .filename("Amélie (2001).mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    mediaFileId = mediaFile.getId();
    var originalTimestamp = Instant.parse("2020-01-01T00:00:00Z");
    setLastModifiedOn(mediaFileId, originalTimestamp);

    try (var connection = dataSource.getConnection()) {
      new V049__Derive_Media_File_Filename_From_Filepath_Uri()
          .migrate(new MigrationContext(connection));
    }
    entityManager.clear();

    assertThat(mediaFileRepository.findById(mediaFileId).orElseThrow().getLastModifiedOn())
        .isEqualTo(originalTimestamp);
  }

  @Test
  @DisplayName("Should continue migrating valid rows when another filepath URI cannot be decoded")
  void shouldContinueMigratingValidRowsWhenAnotherFilepathUriCannotBeDecoded() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId = library.getId();
    var undecodableMediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .filepathUri("file:///media/caf%E9.mkv")
                .filename("legacy-undecodable-name.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    mediaFileId = undecodableMediaFile.getId();
    var validMediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .filepathUri("file:///media/Am%C3%A9lie%20(2001).mkv")
                .filename("Am��lie (2001).mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    additionalMediaFileId = validMediaFile.getId();

    migrate();
    entityManager.clear();

    assertThat(mediaFileRepository.findById(mediaFileId).orElseThrow().getFilename())
        .isEqualTo("legacy-undecodable-name.mkv");
    assertThat(mediaFileRepository.findById(additionalMediaFileId).orElseThrow().getFilename())
        .isEqualTo("Amélie (2001).mkv");
  }

  @Test
  @DisplayName("Should identify skipped row when filepath URI cannot be decoded")
  void shouldIdentifySkippedRowWhenFilepathUriCannotBeDecoded() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId = library.getId();
    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .filepathUri("file:///media/caf%E9.mkv")
                .filename("legacy-undecodable-name.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    mediaFileId = mediaFile.getId();

    var migrationLogs = migrateAndCaptureLogs();

    assertThat(migrationLogs)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .anySatisfy(
            event -> {
              assertThat(event.getFormattedMessage())
                  .contains(mediaFileId.toString())
                  .contains("file:///media/caf%E9.mkv");
            });
  }

  @Test
  @DisplayName("Should report literal update and skip counts when migration completes")
  void shouldReportLiteralUpdateAndSkipCountsWhenMigrationCompletes() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId = library.getId();
    var undecodableMediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .filepathUri("file:///media/caf%E9.mkv")
                .filename("legacy-undecodable-name.mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    mediaFileId = undecodableMediaFile.getId();
    var validMediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(libraryId)
                .filepathUri("file:///media/Am%C3%A9lie%20(2001).mkv")
                .filename("Am��lie (2001).mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    additionalMediaFileId = validMediaFile.getId();

    var migrationLogs = migrateAndCaptureLogs();

    assertThat(migrationLogs)
        .filteredOn(event -> event.getLevel() == Level.INFO)
        .extracting(ILoggingEvent::getFormattedMessage)
        .contains("Media file filename migration completed: updated=1, skipped=1");
  }

  private void migrate() throws Exception {
    try (var connection = dataSource.getConnection()) {
      new V049__Derive_Media_File_Filename_From_Filepath_Uri()
          .migrate(new MigrationContext(connection));
    }
  }

  private List<ILoggingEvent> migrateAndCaptureLogs() throws Exception {
    var logger =
        (Logger) LoggerFactory.getLogger(V049__Derive_Media_File_Filename_From_Filepath_Uri.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try (var connection = dataSource.getConnection()) {
      new V049__Derive_Media_File_Filename_From_Filepath_Uri()
          .migrate(new MigrationContext(connection));
    } finally {
      logger.detachAppender(appender);
    }

    return List.copyOf(appender.list);
  }

  private void setLastModifiedOn(UUID id, Instant timestamp) throws Exception {
    try (var connection = dataSource.getConnection();
        var update =
            connection.prepareStatement(
                "UPDATE media_file SET last_modified_on = ? WHERE id = ?")) {
      update.setObject(1, timestamp.atOffset(ZoneOffset.UTC));
      update.setObject(2, id);
      update.executeUpdate();
    }
    entityManager.clear();
  }

  private record MigrationContext(Connection connection) implements Context {

    @Override
    public Configuration getConfiguration() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Connection getConnection() {
      return connection;
    }
  }
}
