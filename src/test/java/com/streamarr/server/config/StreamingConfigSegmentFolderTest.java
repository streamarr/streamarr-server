package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.properties.StreamingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Streaming Config Segment Folder Tests")
class StreamingConfigSegmentFolderTest {

  private static final byte[] SEGMENT = "segment".getBytes();

  private final StreamingConfig config = new StreamingConfig();

  @Test
  @DisplayName(
      "Should delete what an earlier server process left when the server starts with a segment"
          + " folder of its own")
  void shouldDeleteWhatAnEarlierServerProcessLeftWhenTheServerStartsWithASegmentFolderOfItsOwn(
      @TempDir Path temp) {
    var segmentFolder = temp.resolve("segments");
    var properties =
        StreamingProperties.builder().segmentBasePath(segmentFolder.toString()).build();
    var sessionId = UUID.randomUUID();
    var storeOfEarlierProcess = config.segmentStore(properties);
    try (var stored = storeOfEarlierProcess.prepareSegment(sessionId, "720p/0.m4s", SEGMENT)) {
      stored.publish();
    }

    // Still open: the process ended before it published or discarded the upload.
    try (var _ = storeOfEarlierProcess.prepareSegment(sessionId, "720p/1.m4s", SEGMENT)) {
      config.segmentStore(properties);

      assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
      assertThat(segmentFolder).isDirectoryNotContaining("glob:**/.upload-*");
    }
  }

  // The shared folder carries a mark here as a server's own folder does, so that only the server's
  // choice of folder keeps the directory.
  @Test
  @DisplayName(
      "Should keep the directory of another server process when the server starts with the shared"
          + " segment folder")
  void shouldKeepTheDirectoryOfAnotherServerProcessWhenTheServerStartsWithTheSharedSegmentFolder()
      throws IOException {
    var properties = StreamingProperties.builder().build();
    var sharedFolder = Files.createDirectories(Path.of(properties.segmentBasePath()));
    var mark =
        Files.writeString(
            sharedFolder.resolve(".streamarr-segment-folder"),
            sharedFolder.toRealPath().toString());
    var directoryOfAnotherProcess =
        Files.createDirectory(sharedFolder.resolve(UUID.randomUUID().toString()));
    try {
      config.segmentStore(properties);

      assertThat(directoryOfAnotherProcess).exists();
    } finally {
      Files.deleteIfExists(directoryOfAnotherProcess);
      Files.deleteIfExists(mark);
    }
  }
}
