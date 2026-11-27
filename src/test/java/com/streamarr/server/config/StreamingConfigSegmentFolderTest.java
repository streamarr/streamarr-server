package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.properties.StreamingProperties;
import java.nio.ByteBuffer;
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
    try (var stored = storeOfEarlierProcess.prepareSegment(sessionId, "720p/segment0.m4s")) {
      stored.append(ByteBuffer.wrap(SEGMENT));
      stored.publish();
    }

    // Still open: the process ended before it published or discarded the upload.
    try (var unpublished = storeOfEarlierProcess.prepareSegment(sessionId, "720p/segment1.m4s")) {
      unpublished.append(ByteBuffer.wrap(SEGMENT));
      config.segmentStore(properties);

      assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
      assertThat(segmentFolder).isDirectoryNotContaining("glob:**/.upload-*");
    }
  }
}
