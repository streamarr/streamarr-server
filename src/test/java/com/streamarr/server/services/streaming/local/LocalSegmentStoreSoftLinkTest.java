package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
}
