package com.streamarr.server.services.filepath;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import db.migration.V049__Derive_Media_File_Filename_From_Filepath_Uri;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Media File Filename Migration Integration Tests")
class MediaFileFilenameMigrationIT extends AbstractIntegrationTest {

  @Autowired private DataSource dataSource;
  @Autowired private EntityManager entityManager;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;

  private UUID mediaFileId;
  private UUID libraryId;

  @AfterEach
  void cleanUp() {
    if (mediaFileId == null) {
      return;
    }

    mediaFileRepository.deleteById(mediaFileId);
    libraryRepository.deleteById(libraryId);
  }

  @Test
  @DisplayName("Should derive existing media filename from filepath URI")
  void shouldDeriveExistingMediaFilenameFromFilepathUri() throws Exception {
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
