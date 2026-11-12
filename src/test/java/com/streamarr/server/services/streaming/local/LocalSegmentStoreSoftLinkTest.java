package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Feature;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.exceptions.UnsupportedSegmentFolderException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Local Segment Store Soft Link Tests")
class LocalSegmentStoreSoftLinkTest {

  @TempDir Path segmentFolder;
  @TempDir Path outside;

  @Test
  @DisplayName(
      "Should keep a folder outside the segment folder when a deleted stream session's directory"
          + " links to it")
  void shouldKeepAFolderOutsideTheSegmentFolderWhenADeletedStreamSessionsDirectoryLinksToIt()
      throws IOException {
    var store = new LocalSegmentStore(segmentFolder);
    var sessionId = UUID.randomUUID();
    var sessionDirectory = store.getOutputDirectory(sessionId);
    var outsideFile = Files.write(outside.resolve("movie.mkv"), "media".getBytes());
    Files.createSymbolicLink(sessionDirectory.resolve("link"), outside);

    store.deleteSession(sessionId);

    assertThat(outsideFile).exists();
    assertThat(sessionDirectory).doesNotExist();
  }

  // Without secure directory streams, a delete cannot rule out that a soft link replaces a
  // directory while the delete runs. Windows has none.
  @Test
  @DisplayName(
      "Should refuse the segment folder when its file system cannot delete a directory without"
          + " following soft links")
  void
      shouldRefuseTheSegmentFolderWhenItsFileSystemCannotDeleteADirectoryWithoutFollowingSoftLinks()
          throws IOException {
    var withoutSecureDirectoryStreams =
        Configuration.unix().toBuilder()
            .setSupportedFeatures(Feature.LINKS, Feature.SYMBOLIC_LINKS, Feature.FILE_CHANNEL)
            .build();
    try (var fileSystem = Jimfs.newFileSystem(withoutSecureDirectoryStreams)) {
      var folder = fileSystem.getPath("/segments");

      assertThatThrownBy(() -> new LocalSegmentStore(folder))
          .isInstanceOf(UnsupportedSegmentFolderException.class)
          .hasMessageContaining("/segments");
    }
  }

  @Test
  @DisplayName("Should fail when the segment folder cannot be created")
  void shouldFailWhenTheSegmentFolderCannotBeCreated() throws IOException {
    var regularFile = Files.createFile(outside.resolve("not-a-folder"));
    var folder = regularFile.resolve("segments");

    assertThatThrownBy(() -> new LocalSegmentStore(folder))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining(folder.toString());
  }
}
