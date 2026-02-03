package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.exceptions.TranscodeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
class LocalSegmentStoreTest {

  @TempDir Path tempDir;

  private LocalSegmentStore store;

  @BeforeEach
  void setUp() {
    store = new LocalSegmentStore(tempDir);
  }

  @AfterEach
  void tearDown() {
    store.shutdown();
  }

  @Test
  @DisplayName("Should create output directory when session is first accessed")
  void shouldCreateOutputDirectoryWhenSessionIsFirstAccessed() {
    var sessionId = UUID.randomUUID();

    var outputDir = store.getOutputDirectory(sessionId);

    assertThat(outputDir).exists().isDirectory();
    assertThat(outputDir.getParent()).isEqualTo(tempDir);
  }

  @Test
  @DisplayName("Should return same output directory when called twice for same session")
  void shouldReturnSameOutputDirectoryWhenCalledTwiceForSameSession() {
    var sessionId = UUID.randomUUID();

    var first = store.getOutputDirectory(sessionId);
    var second = store.getOutputDirectory(sessionId);

    assertThat(first).isEqualTo(second);
  }

  @Test
  @DisplayName("Should read segment data when segment file exists")
  void shouldReadSegmentDataWhenSegmentFileExists() throws IOException {
    var sessionId = UUID.randomUUID();
    var outputDir = store.getOutputDirectory(sessionId);
    var expectedBytes = "segment data".getBytes();
    Files.write(outputDir.resolve("segment0.ts"), expectedBytes);

    var result = store.readSegment(sessionId, "segment0.ts");

    assertThat(result).isEqualTo(expectedBytes);
  }

  @Test
  @DisplayName("Should throw when reading nonexistent segment")
  void shouldThrowWhenReadingNonexistentSegment() {
    var sessionId = UUID.randomUUID();
    store.getOutputDirectory(sessionId);

    assertThatThrownBy(() -> store.readSegment(sessionId, "missing.ts"))
        .isInstanceOf(TranscodeException.class);
  }

  @Test
  @DisplayName("Should delete directory and contents when session is deleted")
  void shouldDeleteDirectoryAndContentsWhenSessionIsDeleted() throws IOException {
    var sessionId = UUID.randomUUID();
    var outputDir = store.getOutputDirectory(sessionId);
    Files.write(outputDir.resolve("segment0.ts"), "data".getBytes());

    store.deleteSession(sessionId);

    assertThat(outputDir).doesNotExist();
  }

  @Test
  @DisplayName("Should not throw when deleting nonexistent session")
  void shouldNotThrowWhenDeletingNonexistentSession() {
    var sessionId = UUID.randomUUID();

    store.deleteSession(sessionId);
  }

  @Test
  @DisplayName("Should return true immediately when waiting for existing segment")
  void shouldReturnTrueImmediatelyWhenWaitingForExistingSegment() throws IOException {
    var sessionId = UUID.randomUUID();
    var outputDir = store.getOutputDirectory(sessionId);
    Files.write(outputDir.resolve("segment0.ts"), "data".getBytes());

    var result = store.waitForSegment(sessionId, "segment0.ts", Duration.ofSeconds(1));

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("Should timeout when segment never appears")
  void shouldTimeoutWhenSegmentNeverAppears() {
    var sessionId = UUID.randomUUID();
    store.getOutputDirectory(sessionId);

    var result = store.waitForSegment(sessionId, "missing.ts", Duration.ofMillis(300));

    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("Should reject segment name when path traversal is attempted")
  void shouldRejectSegmentNameWhenPathTraversalIsAttempted() {
    var sessionId = UUID.randomUUID();
    store.getOutputDirectory(sessionId);

    assertThatThrownBy(() -> store.readSegment(sessionId, "../../etc/passwd.ts"))
        .isInstanceOf(InvalidSegmentPathException.class);
  }

  @Test
  @DisplayName("Should read segment when name includes valid subdirectory")
  void shouldReadSegmentWhenNameIncludesValidSubdirectory() throws IOException {
    var sessionId = UUID.randomUUID();
    var outputDir = store.getOutputDirectory(sessionId);
    var variantDir = outputDir.resolve("720p");
    Files.createDirectories(variantDir);
    Files.write(variantDir.resolve("segment0.ts"), "data".getBytes());

    var result = store.readSegment(sessionId, "720p/segment0.ts");

    assertThat(result).isEqualTo("data".getBytes());
  }

  @Test
  @DisplayName("Should return true when segment is created by background thread")
  void shouldReturnTrueWhenSegmentIsCreatedByBackgroundThread() {
    var sessionId = UUID.randomUUID();
    var outputDir = store.getOutputDirectory(sessionId);

    var executor = Executors.newSingleThreadScheduledExecutor();
    executor.schedule(
        () -> {
          try {
            Files.write(outputDir.resolve("segment1.ts"), "delayed data".getBytes());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        },
        200,
        TimeUnit.MILLISECONDS);

    var result = store.waitForSegment(sessionId, "segment1.ts", Duration.ofSeconds(5));

    assertThat(result).isTrue();
    executor.shutdown();
  }
}
