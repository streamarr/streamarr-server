package com.streamarr.server.services.filepath;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.support.AuditActorSetting;
import com.streamarr.server.support.AuditColumnFixtures;
import db.migration.V049__Derive_Media_File_Filename_From_Filepath_Uri;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@Isolated
@DisplayName("Media File Filename Migration Integration Tests")
class MediaFileFilenameMigrationIT extends AbstractIntegrationTest {

  @Autowired private DataSource dataSource;
  @Autowired private EntityManager entityManager;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private AuditColumnFixtures auditColumnFixtures;

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
                .filepathUri("file:///media/Caf%C3%A9%20Meridian%20(2006).mkv")
                .filename("Caf�� Meridian (2006).mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    mediaFileId = mediaFile.getId();

    migrate();
    entityManager.clear();

    assertThat(mediaFileRepository.findById(mediaFileId).orElseThrow().getFilename())
        .isEqualTo("Café Meridian (2006).mkv");
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
                .filepathUri("file:///media/Caf%C3%A9%20Meridian%20(2001).mkv")
                .filename("Café Meridian (2001).mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    mediaFileId = mediaFile.getId();
    var originalTimestamp = Instant.parse("2020-01-01T00:00:00Z");
    setLastModifiedOn(mediaFileId, originalTimestamp);

    migrate();
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
                .filepathUri("file:///media/Caf%C3%A9%20Meridian%20(2001).mkv")
                .filename("Caf�� Meridian (2001).mkv")
                .status(MediaFileStatus.MATCHED)
                .build());
    additionalMediaFileId = validMediaFile.getId();

    migrate();
    entityManager.clear();

    assertThat(mediaFileRepository.findById(mediaFileId).orElseThrow().getFilename())
        .isEqualTo("legacy-undecodable-name.mkv");
    assertThat(mediaFileRepository.findById(additionalMediaFileId).orElseThrow().getFilename())
        .isEqualTo("Café Meridian (2001).mkv");
  }

  // V049 ran before the audit trigger existed, so it names no actor. The trigger exists in this
  // database, so the test names the System actor for it.
  private void migrate() throws Exception {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      AuditActorSetting.nameActorIn(connection, SystemActor.ID.toString());
      new V049__Derive_Media_File_Filename_From_Filepath_Uri()
          .migrate(new MigrationContext(connection));
      connection.commit();
    }
  }

  private void setLastModifiedOn(UUID id, Instant timestamp) {
    auditColumnFixtures.overwriteLastModifiedOn(MEDIA_FILE, MEDIA_FILE.ID.eq(id), timestamp);
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
