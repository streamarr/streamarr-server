package com.streamarr.transcode.engine.segment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.error.TranscodeException;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Local Segment Storage Tests")
class LocalSegmentStorageTest {

  @TempDir Path tempDir;
  @TempDir Path externalDir;

  private LocalSegmentStorage storage;

  @BeforeEach
  void setUp() {
    storage = new LocalSegmentStorage(tempDir);
  }

  @Test
  @DisplayName("Should hide generation artifacts until the generation is published")
  void shouldHideGenerationArtifactsUntilGenerationIsPublished() throws IOException {
    var sessionId = UUID.randomUUID();
    var generation =
        storage.prepareGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1));
    var renditionDirectory = Files.createDirectories(generation.outputDirectory().resolve("720p"));
    Files.writeString(renditionDirectory.resolve("segment0.ts"), "staged");

    assertThat(storage.segmentExists(sessionId, "720p/segment0.ts")).isFalse();
    assertThatThrownBy(() -> storage.readSegment(sessionId, "720p/segment0.ts"))
        .isInstanceOf(TranscodeException.class);
  }

  @Test
  @DisplayName("Should expose every generation artifact when the generation is published")
  void shouldExposeEveryGenerationArtifactWhenGenerationIsPublished() throws IOException {
    var sessionId = UUID.randomUUID();
    var generation =
        storage.prepareGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1));
    var lowRendition = Files.createDirectories(generation.outputDirectory().resolve("480p"));
    var highRendition = Files.createDirectories(generation.outputDirectory().resolve("1080p"));
    Files.writeString(lowRendition.resolve("segment0.ts"), "low");
    Files.writeString(highRendition.resolve("segment0.ts"), "high");

    storage.publish(generation);

    assertThat(storage.readSegment(sessionId, "480p/segment0.ts")).isEqualTo("low".getBytes());
    assertThat(storage.readSegment(sessionId, "1080p/segment0.ts")).isEqualTo("high".getBytes());
  }

  @Test
  @DisplayName("Should preserve the published generation when its replacement is discarded")
  void shouldPreservePublishedGenerationWhenReplacementIsDiscarded() throws IOException {
    var sessionId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var published = writeGeneration(sessionId, new TranscodeJobRef(jobId, 1), "published");
    storage.publish(published);
    var replacement = writeGeneration(sessionId, new TranscodeJobRef(jobId, 2), "replacement");

    storage.discard(replacement);

    assertThat(storage.readSegment(sessionId, "720p/segment0.ts"))
        .isEqualTo("published".getBytes());
    assertThat(replacement.outputDirectory()).doesNotExist();
  }

  @Test
  @DisplayName("Should prefer a replacement while retaining earlier timeline segments")
  void shouldPreferReplacementWhileRetainingEarlierTimelineSegments() throws IOException {
    var sessionId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var original = storage.prepareGeneration(sessionId, new TranscodeJobRef(jobId, 1));
    var originalDirectory = Files.createDirectories(original.outputDirectory().resolve("720p"));
    Files.writeString(originalDirectory.resolve("segment0.ts"), "original-zero");
    Files.writeString(originalDirectory.resolve("segment1.ts"), "original-one");
    storage.publish(original);
    var replacement = storage.prepareGeneration(sessionId, new TranscodeJobRef(jobId, 2));
    var replacementDirectory =
        Files.createDirectories(replacement.outputDirectory().resolve("720p"));
    Files.writeString(replacementDirectory.resolve("segment1.ts"), "replacement-one");

    storage.publish(replacement);

    assertThat(storage.readSegment(sessionId, "720p/segment1.ts"))
        .isEqualTo("replacement-one".getBytes());
    assertThat(storage.readSegment(sessionId, "720p/segment0.ts"))
        .isEqualTo("original-zero".getBytes());
  }

  @Test
  @DisplayName("Should reject publication of a superseded prepared generation")
  void shouldRejectPublicationOfSupersededPreparedGeneration() throws IOException {
    var sessionId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var superseded = writeGeneration(sessionId, new TranscodeJobRef(jobId, 1), "superseded");
    var current = writeGeneration(sessionId, new TranscodeJobRef(jobId, 2), "current");
    storage.publish(current);

    assertThatThrownBy(() -> storage.publish(superseded)).isInstanceOf(IllegalStateException.class);
    assertThat(storage.readSegment(sessionId, "720p/segment0.ts")).isEqualTo("current".getBytes());
  }

  @Test
  @DisplayName("Should ignore withdrawal for an older generation")
  void shouldIgnoreWithdrawalForOlderGeneration() throws IOException {
    var sessionId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var original = writeGeneration(sessionId, new TranscodeJobRef(jobId, 1), "original");
    storage.publish(original);
    var replacement = writeGeneration(sessionId, new TranscodeJobRef(jobId, 2), "replacement");
    storage.publish(replacement);

    var withdrawn = storage.withdraw(sessionId, original.jobRef());

    assertThat(withdrawn).isFalse();
    assertThat(storage.readSegment(sessionId, "720p/segment0.ts"))
        .isEqualTo("replacement".getBytes());
  }

  @Test
  @DisplayName("Should hide the complete timeline when the current generation is withdrawn")
  void shouldHideCompleteTimelineWhenCurrentGenerationIsWithdrawn() throws IOException {
    var sessionId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var original = writeGeneration(sessionId, new TranscodeJobRef(jobId, 1), "original");
    storage.publish(original);
    var replacement = storage.prepareGeneration(sessionId, new TranscodeJobRef(jobId, 2));
    var replacementDirectory =
        Files.createDirectories(replacement.outputDirectory().resolve("720p"));
    Files.writeString(replacementDirectory.resolve("segment1.ts"), "replacement");
    storage.publish(replacement);

    var withdrawn = storage.withdraw(sessionId, replacement.jobRef());

    assertThat(withdrawn).isTrue();
    assertThat(storage.segmentExists(sessionId, "720p/segment0.ts")).isFalse();
    assertThat(storage.segmentExists(sessionId, "720p/segment1.ts")).isFalse();
  }

  @Test
  @DisplayName("Should exclude a failed generation while retaining safe history on recovery")
  void shouldExcludeFailedGenerationWhileRetainingSafeHistoryOnRecovery() throws IOException {
    var sessionId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var original = storage.prepareGeneration(sessionId, new TranscodeJobRef(jobId, 1));
    writeSegment(original, "segment0.ts", "safe-history");
    storage.publish(original);
    var failed = storage.prepareGeneration(sessionId, new TranscodeJobRef(jobId, 2));
    writeSegment(failed, "segment1.ts", "failed-generation");
    storage.publish(failed);
    storage.withdraw(sessionId, failed.jobRef());
    var recovered = storage.prepareGeneration(sessionId, new TranscodeJobRef(jobId, 3));
    writeSegment(recovered, "segment2.ts", "recovered");

    storage.publish(recovered);

    assertThat(storage.readSegment(sessionId, "720p/segment2.ts"))
        .isEqualTo("recovered".getBytes());
    assertThat(storage.readSegment(sessionId, "720p/segment0.ts"))
        .isEqualTo("safe-history".getBytes());
    assertThat(storage.segmentExists(sessionId, "720p/segment1.ts")).isFalse();
  }

  @Test
  @DisplayName("Should invalidate and delete a prepared generation when its session is deleted")
  void shouldInvalidateAndDeletePreparedGenerationWhenSessionIsDeleted() throws IOException {
    var sessionId = UUID.randomUUID();
    var generation =
        writeGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1), "prepared-segment");

    storage.deleteSession(sessionId);

    assertThat(generation.outputDirectory()).doesNotExist();
    assertThatThrownBy(() -> storage.publish(generation)).isInstanceOf(IllegalStateException.class);
    assertThat(storage.segmentExists(sessionId, "720p/segment0.ts")).isFalse();
  }

  @Test
  @DisplayName("Should observe a generation published after waiting begins")
  void shouldObserveGenerationPublishedAfterWaitingBegins() throws Exception {
    var sessionId = UUID.randomUUID();
    var generation =
        writeGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1), "published-later");
    var entered = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting =
          executor.submit(
              () -> {
                entered.countDown();
                return storage.waitForSegment(
                    sessionId, "720p/segment0.ts", java.time.Duration.ofSeconds(2));
              });
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(150);
      assertThat(waiting.isDone()).isFalse();

      storage.publish(generation);

      assertThat(waiting.get(1, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("Should stop observing a generation withdrawn while waiting")
  void shouldStopObservingGenerationWithdrawnWhileWaiting() throws Exception {
    var sessionId = UUID.randomUUID();
    var generation =
        storage.prepareGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1));
    Files.createDirectories(generation.outputDirectory().resolve("720p"));
    storage.publish(generation);
    var entered = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting =
          executor.submit(
              () -> {
                entered.countDown();
                return storage.waitForSegment(
                    sessionId, "720p/segment1.ts", java.time.Duration.ofMillis(400));
              });
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(150);

      storage.withdraw(sessionId, generation.jobRef());
      writeSegment(generation, "segment1.ts", "too-late");

      assertThat(waiting.get(1, TimeUnit.SECONDS)).isFalse();
    }
  }

  @Test
  @DisplayName("Should discover a generated session after storage restarts")
  void shouldDiscoverGeneratedSessionAfterStorageRestarts() {
    var sessionId = UUID.randomUUID();
    storage.prepareGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1));
    storage = new LocalSegmentStorage(tempDir);

    var storedSessionIds = storage.snapshotStoredSessionIds();

    assertThat(storedSessionIds).containsExactly(sessionId);
  }

  @Test
  @DisplayName("Should delete a generated session after storage restarts")
  void shouldDeleteGeneratedSessionAfterStorageRestarts() {
    var sessionId = UUID.randomUUID();
    var generation =
        storage.prepareGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1));
    storage = new LocalSegmentStorage(tempDir);

    storage.deleteSession(sessionId);

    assertThat(generation.outputDirectory()).doesNotExist();
    assertThat(storage.snapshotStoredSessionIds()).doesNotContain(sessionId);
  }

  @Test
  @DisplayName("Should reject discarding a generation owned by another storage instance")
  void shouldRejectDiscardingGenerationOwnedByAnotherStorageInstance() throws IOException {
    var generation =
        writeGeneration(
            UUID.randomUUID(), new TranscodeJobRef(UUID.randomUUID(), 1), "owned-segment");
    var otherStorage = new LocalSegmentStorage(tempDir);

    assertThatThrownBy(() -> otherStorage.discard(generation))
        .isInstanceOf(IllegalStateException.class);
    assertThat(generation.outputDirectory()).exists();
  }

  @Test
  @DisplayName("Should reject publishing a generation owned by another storage instance")
  void shouldRejectPublishingGenerationOwnedByAnotherStorageInstance() throws IOException {
    var generation =
        writeGeneration(
            UUID.randomUUID(), new TranscodeJobRef(UUID.randomUUID(), 1), "owned-segment");
    var otherStorage = new LocalSegmentStorage(tempDir);

    assertThatThrownBy(() -> otherStorage.publish(generation))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Should reject discarding a generation after it is published")
  void shouldRejectDiscardingGenerationAfterItIsPublished() throws IOException {
    var sessionId = UUID.randomUUID();
    var generation =
        writeGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1), "published-segment");
    storage.publish(generation);

    assertThatThrownBy(() -> storage.discard(generation)).isInstanceOf(IllegalStateException.class);
    assertThat(storage.readSegment(sessionId, "720p/segment0.ts"))
        .isEqualTo("published-segment".getBytes());
  }

  @Test
  @DisplayName("Should reject a segment symlink outside the published generation")
  void shouldRejectSegmentSymlinkOutsidePublishedGeneration() throws IOException {
    var sessionId = UUID.randomUUID();
    var generation =
        storage.prepareGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1));
    var renditionDirectory = Files.createDirectories(generation.outputDirectory().resolve("720p"));
    var externalFile = Files.writeString(externalDir.resolve("secret.ts"), "external-secret");
    Files.createSymbolicLink(renditionDirectory.resolve("segment0.ts"), externalFile);
    storage.publish(generation);

    assertThatThrownBy(() -> storage.readSegment(sessionId, "720p/segment0.ts"))
        .isInstanceOf(InvalidSegmentPathException.class);
  }

  @Test
  @DisplayName("Should reject reusing residual artifacts for the same generation after restart")
  void shouldRejectReusingResidualArtifactsForSameGenerationAfterRestart() throws IOException {
    var sessionId = UUID.randomUUID();
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);
    writeGeneration(sessionId, jobRef, "residual-segment");
    storage = new LocalSegmentStorage(tempDir);

    assertThatThrownBy(() -> storage.prepareGeneration(sessionId, jobRef))
        .isInstanceOf(IllegalStateException.class);
  }

  private SegmentGeneration writeGeneration(
      UUID sessionId, TranscodeJobRef jobRef, String segmentContents) throws IOException {
    var generation = storage.prepareGeneration(sessionId, jobRef);
    writeSegment(generation, "segment0.ts", segmentContents);
    return generation;
  }

  private static void writeSegment(
      SegmentGeneration generation, String segmentName, String segmentContents) throws IOException {
    var renditionDirectory = Files.createDirectories(generation.outputDirectory().resolve("720p"));
    Files.writeString(renditionDirectory.resolve(segmentName), segmentContents);
  }
}
