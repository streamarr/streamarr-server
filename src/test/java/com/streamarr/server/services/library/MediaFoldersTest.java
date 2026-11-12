package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.properties.RemoteTranscodeProperties;
import java.nio.file.FileSystem;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Media Folders Tests")
class MediaFoldersTest {

  private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());
  private final FakeLibraryRepository libraryRepository = new FakeLibraryRepository();
  private final MediaFolders mediaFolders =
      new MediaFolders(
          fileSystem,
          new RemoteTranscodeProperties(UUID.randomUUID(), "/media"),
          libraryRepository);

  @AfterEach
  void closeFileSystem() throws Exception {
    fileSystem.close();
  }

  @Test
  @DisplayName("Should list the media root and each library's folder when libraries exist")
  void shouldListTheMediaRootAndEachLibrarysFolderWhenLibrariesExist() {
    saveLibrary("/media/movies");
    saveLibrary("/srv/shows");

    var folders = mediaFolders.all();

    assertThat(folders)
        .containsExactlyInAnyOrder(
            fileSystem.getPath("/media"),
            fileSystem.getPath("/media/movies"),
            fileSystem.getPath("/srv/shows"));
  }

  @Test
  @DisplayName("Should list the media root alone when no library exists")
  void shouldListTheMediaRootAloneWhenNoLibraryExists() {
    assertThat(mediaFolders.all()).containsExactly(fileSystem.getPath("/media"));
  }

  @Test
  @DisplayName("Should throw when a library's stored folder cannot be decoded")
  void shouldThrowWhenALibrarysStoredFolderCannotBeDecoded() {
    libraryRepository.save(
        LibraryFixtureCreator.unsavedLibraryBuilder()
            .name("Movies")
            .filepathUri("not a file URI")
            .build());

    assertThatIllegalArgumentException().isThrownBy(mediaFolders::all);
  }

  private void saveLibrary(String folder) {
    libraryRepository.save(
        LibraryFixtureCreator.unsavedLibraryBuilder()
            .name(folder)
            .filepathUri("file://" + folder)
            .build());
  }
}
