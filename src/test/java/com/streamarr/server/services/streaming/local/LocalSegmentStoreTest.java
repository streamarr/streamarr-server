package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
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

    assertThat(outputDir).exists().isDirectory().hasParentRaw(tempDir);
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

    assertThatNoException().isThrownBy(() -> store.deleteSession(sessionId));
  }

  @Test
  @DisplayName("Should return true immediately when waiting for existing segment")
  void shouldReturnTrueImmediatelyWhenWaitingForExistingSegment() throws IOException {
    var sessionId = UUID.randomUUID();
    var outputDir = store.getOutputDirectory(sessionId);
    Files.write(outputDir.resolve("segment0.ts"), "data".getBytes());

    var result = store.waitForSegment(sessionId, "segment0.ts", () -> true);

    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("Should timeout when segment never appears")
  void shouldTimeoutWhenSegmentNeverAppears() {
    var sessionId = UUID.randomUUID();
    store.getOutputDirectory(sessionId);

    var result = store.waitForSegment(sessionId, "missing.ts", () -> false);

    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("Should time out instead of throwing when session has no output directory")
  void shouldTimeOutInsteadOfThrowingWhenSessionHasNoOutputDirectory() {
    var sessionId = UUID.randomUUID();

    var result = store.waitForSegment(sessionId, "segment0.ts", () -> false);

    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("Should return true when first upload creates the session directory during wait")
  void shouldReturnTrueWhenFirstUploadCreatesSessionDirectoryDuringWait() {
    var sessionId = UUID.randomUUID();

    var executor = Executors.newSingleThreadScheduledExecutor();
    executor.schedule(
        () -> store.storeSegment(sessionId, "720p/segment0.ts", "uploaded".getBytes()),
        200,
        TimeUnit.MILLISECONDS);

    var result = store.waitForSegment(sessionId, "720p/segment0.ts", () -> true);

    assertThat(result).isTrue();
    executor.shutdown();
  }

  @Test
  @DisplayName("Should keep waiting while producer is alive then return when segment appears")
  void shouldWaitWhileProducerIsAliveThenReturnWhenSegmentAppears() throws InterruptedException {
    var sessionId = UUID.randomUUID();
    var publisher =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    Thread.sleep(400);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                  store.storeSegment(sessionId, "segment0.ts", "late data".getBytes());
                });

    var result = store.waitForSegment(sessionId, "segment0.ts", () -> true);

    assertThat(result).isTrue();
    publisher.join();
  }

  @Test
  @DisplayName("Should stop waiting promptly when producer is dead")
  void shouldStopWaitingPromptlyWhenProducerIsDead() {
    var sessionId = UUID.randomUUID();

    var startedAt = System.nanoTime();
    var result = store.waitForSegment(sessionId, "never.ts", () -> false);
    var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertThat(result).isFalse();
    assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
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
  @DisplayName("Should store a complete segment in its variant directory")
  void shouldStoreCompleteSegmentInVariantDirectory() {
    var sessionId = UUID.randomUUID();
    var segmentData = "remote segment".getBytes();

    store.storeSegment(sessionId, "720p/segment0.ts", segmentData);

    assertThat(store.readSegment(sessionId, "720p/segment0.ts")).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should not expose a prepared segment until it is published")
  void shouldNotExposePreparedSegmentUntilItIsPublished() {
    var sessionId = UUID.randomUUID();
    var segmentData = "remote segment".getBytes();

    try (var prepared = store.prepareSegment(sessionId, "720p/segment0.ts", segmentData)) {
      assertThat(tempDir.resolve(sessionId.toString())).doesNotExist();
      assertThat(store.segmentExists(sessionId, "720p/segment0.ts")).isFalse();

      prepared.publish();
    }

    assertThat(store.readSegment(sessionId, "720p/segment0.ts")).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should discard a prepared segment when it is closed without publication")
  void shouldDiscardPreparedSegmentWhenClosedWithoutPublication() {
    var sessionId = UUID.randomUUID();

    try (var _ = store.prepareSegment(sessionId, "720p/segment0.ts", "remote segment".getBytes())) {
      assertThat(tempDir.resolve(sessionId.toString())).doesNotExist();
    }

    assertThat(tempDir).isEmptyDirectory();
  }

  @Test
  @DisplayName("Should clean the temporary file when segment preparation fails")
  void shouldCleanTemporaryFileWhenSegmentPreparationFails() {
    var sessionId = UUID.randomUUID();

    assertThatThrownBy(() -> store.prepareSegment(sessionId, "segment0.ts", null))
        .isInstanceOf(NullPointerException.class);
    assertThat(tempDir).isEmptyDirectory();
  }

  @Test
  @DisplayName("Should reject a stored segment that escapes its session directory")
  void shouldRejectStoredSegmentThatEscapesSessionDirectory() {
    var sessionId = UUID.randomUUID();
    var segmentData = "data".getBytes();

    assertThatThrownBy(() -> store.storeSegment(sessionId, "../../escaped.ts", segmentData))
        .isInstanceOf(InvalidSegmentPathException.class);
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

    var result = store.waitForSegment(sessionId, "segment1.ts", () -> true);

    assertThat(result).isTrue();
    executor.shutdown();
  }

  @Test
  @DisplayName("Should create variant subdirectory when variant label is not default")
  void shouldCreateVariantSubdirWhenVariantLabelIsNotDefault() {
    var sessionId = UUID.randomUUID();

    var variantDir = store.getOutputDirectory(sessionId, "720p");

    assertThat(variantDir).exists().isDirectory();
    assertThat(variantDir.getFileName()).hasToString("720p");
    assertThat(variantDir).hasParentRaw(store.getOutputDirectory(sessionId));
  }
}
