package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingConfig;
import com.streamarr.server.config.StreamingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// A server that exits without its shutdown hook leaves its segment folder as it was. The next
// server holds none of those stream sessions, so nothing else can ever delete what they stored.
@Tag("UnitTest")
@DisplayName("Local Segment Store Leftover Reclaim Tests")
class LocalSegmentStoreLeftoverReclaimTest {

  @TempDir Path tempDir;

  private Path segmentFolder;

  @BeforeEach
  void setUp() throws IOException {
    segmentFolder = Files.createDirectory(tempDir.resolve("streamarr-segments"));
  }

  @Test
  @DisplayName(
      "Should reclaim a session directory left by an earlier server when the store starts over"
          + " the segment folder")
  void shouldReclaimASessionDirectoryLeftByAnEarlierServerWhenTheStoreStarts() throws IOException {
    var leftoverSession =
        Files.createDirectory(segmentFolder.resolve(UUID.randomUUID().toString()));
    Files.write(leftoverSession.resolve("init.mp4"), new byte[] {1});
    Files.write(leftoverSession.resolve("segment0.m4s"), new byte[] {2});
    Files.write(
        Files.createDirectory(leftoverSession.resolve("720p")).resolve("segment0.m4s"),
        new byte[] {3});

    startThenShutDownTheStore();

    assertThat(leftoverSession).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should reclaim an unpublished upload file left by an earlier server when the store starts"
          + " over the segment folder")
  void shouldReclaimAnUnpublishedUploadFileLeftByAnEarlierServerWhenTheStoreStarts()
      throws IOException {
    var leftoverUpload =
        Files.write(segmentFolder.resolve(".upload-4242424242424242.tmp"), new byte[] {1});

    startThenShutDownTheStore();

    assertThat(leftoverUpload).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should keep an entry it did not write when the store starts over the segment folder")
  void shouldKeepAnEntryItDidNotWriteWhenTheStoreStarts() throws IOException {
    var foreignFile = Files.write(segmentFolder.resolve("notes.txt"), new byte[] {1});
    var foreignDirectory = Files.createDirectory(segmentFolder.resolve("backups"));
    var foreignNestedFile = Files.write(foreignDirectory.resolve("segment0.m4s"), new byte[] {2});

    startThenShutDownTheStore();

    assertThat(foreignFile).exists();
    assertThat(foreignNestedFile).exists();
  }

  @Test
  @DisplayName(
      "Should keep a running server's stream session when a second store starts over the same"
          + " segment folder")
  void shouldKeepARunningServersStreamSessionWhenASecondStoreStartsOverTheSameFolder() {
    var running = startTheStore();
    var sessionId = UUID.randomUUID();
    try (var prepared = running.prepareSegment(sessionId, "segment0.m4s", new byte[] {1})) {
      prepared.publish();
    }

    var second = startTheStore();

    assertThat(running.segmentExists(sessionId, "segment0.m4s")).isTrue();
    second.shutdown();
    running.shutdown();
  }

  // The store as the application builds it, through a whole life: a reclaim at startup or at
  // shutdown both satisfy these tests.
  private void startThenShutDownTheStore() {
    startTheStore().shutdown();
  }

  private LocalSegmentStore startTheStore() {
    var properties =
        StreamingProperties.builder().segmentBasePath(segmentFolder.toString()).build();
    return new StreamingConfig().segmentStore(properties);
  }
}
