package com.streamarr.transcode.engine.segment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.error.TranscodeException;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
  @DisplayName("Should prepare a generation when the managed base directory is missing")
  void shouldPrepareGenerationWhenManagedBaseDirectoryIsMissing() {
    var missingBase = tempDir.resolve("missing/base");
    storage = new LocalSegmentStorage(missingBase);

    var generation =
        storage.prepareGeneration(UUID.randomUUID(), new TranscodeJobRef(UUID.randomUUID(), 1));

    assertThat(generation.outputDirectory()).exists().isDirectory();
  }

  @Test
  @DisplayName("Should reject a regular file as the configured storage base")
  void shouldRejectRegularFileAsConfiguredStorageBase() throws IOException {
    var configuredBase = Files.createFile(tempDir.resolve("segments.file"));

    assertThatThrownBy(() -> new LocalSegmentStorage(configuredBase))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  @DisplayName("Should create a legacy rendition output directory inside its session")
  void shouldCreateLegacyRenditionOutputDirectoryInsideItsSession() {
    var sessionId = UUID.randomUUID();

    var renditionDirectory = storage.getOutputDirectory(sessionId, "720p");
    var sessionDirectory = storage.getOutputDirectory(sessionId);

    assertThat(renditionDirectory).isDirectory();
    assertThat(renditionDirectory).hasParent(sessionDirectory);
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
  @DisplayName("Should report a missing segment after searching the published timeline")
  void shouldReportMissingSegmentAfterSearchingPublishedTimeline() throws IOException {
    var sessionId = UUID.randomUUID();
    var generation =
        storage.prepareGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1));
    Files.writeString(generation.outputDirectory().resolve("stream.m3u8"), "#EXTM3U");
    storage.publish(generation);

    assertThatThrownBy(() -> storage.readSegment(sessionId, "segment0.ts"))
        .isInstanceOf(TranscodeException.class)
        .hasMessage("Segment not found: segment0.ts");
  }

  @Test
  @DisplayName("Should preserve interruption while waiting for a segment")
  void shouldPreserveInterruptionWhileWaitingForSegment() {
    var sessionId = UUID.randomUUID();
    storage.getOutputDirectory(sessionId);
    Thread.currentThread().interrupt();

    try {
      assertThat(storage.waitForSegment(sessionId, "segment0.ts", Duration.ofSeconds(1))).isFalse();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
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
  @DisplayName("Should reject republishing a withdrawn generation")
  void shouldRejectRepublishingWithdrawnGeneration() throws IOException {
    var sessionId = UUID.randomUUID();
    var generation =
        writeGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1), "withdrawn-segment");
    storage.publish(generation);
    storage.withdraw(sessionId, generation.jobRef());

    assertThatThrownBy(() -> storage.publish(generation)).isInstanceOf(IllegalStateException.class);
    assertThat(storage.segmentExists(sessionId, "720p/segment0.ts")).isFalse();
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
    var observedStorage = new ObservableSegmentStorage(tempDir);
    storage = observedStorage;
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
      assertThat(observedStorage.awaitFirstSegmentCheck()).isTrue();
      assertThat(waiting.isDone()).isFalse();

      storage.publish(generation);
      observedStorage.allowSegmentChecks();

      assertThat(waiting.get(1, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("Should stop observing a generation withdrawn while waiting")
  void shouldStopObservingGenerationWithdrawnWhileWaiting() throws Exception {
    var observedStorage = new ObservableSegmentStorage(tempDir);
    storage = observedStorage;
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
      assertThat(observedStorage.awaitFirstSegmentCheck()).isTrue();

      storage.withdraw(sessionId, generation.jobRef());
      writeSegment(generation, "segment1.ts", "too-late");
      observedStorage.allowSegmentChecks();

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
  @DisplayName("Should reject a generation root replaced by an external symlink")
  void shouldRejectGenerationRootReplacedByExternalSymlink() throws IOException {
    var sessionId = UUID.randomUUID();
    var generation =
        storage.prepareGeneration(sessionId, new TranscodeJobRef(UUID.randomUUID(), 1));
    Files.delete(generation.outputDirectory());
    var externalGeneration = Files.createDirectories(externalDir.resolve("generation/720p"));
    Files.writeString(externalGeneration.resolve("segment0.ts"), "external-secret");
    Files.createSymbolicLink(
        generation.outputDirectory(), externalGeneration.getParent().toAbsolutePath());
    storage.publish(generation);

    assertThatThrownBy(() -> storage.readSegment(sessionId, "720p/segment0.ts"))
        .isInstanceOf(InvalidSegmentPathException.class);
  }

  @Test
  @DisplayName("Should reject a staging directory symlink outside managed storage")
  void shouldRejectStagingDirectorySymlinkOutsideManagedStorage() throws IOException {
    var sessionId = UUID.randomUUID();
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);
    Files.createSymbolicLink(tempDir.resolve(".staging"), externalDir.toAbsolutePath());

    assertThatThrownBy(() -> storage.prepareGeneration(sessionId, jobRef))
        .isInstanceOf(InvalidSegmentPathException.class);
    assertThat(externalDir.resolve(sessionId.toString())).doesNotExist();
  }

  @Test
  @DisplayName("Should reject a legacy session directory symlink outside managed storage")
  void shouldRejectLegacySessionDirectorySymlinkOutsideManagedStorage() throws IOException {
    var sessionId = UUID.randomUUID();
    var externalSession = Files.createDirectories(externalDir.resolve(sessionId.toString()));
    Files.createSymbolicLink(
        tempDir.resolve(sessionId.toString()), externalSession.toAbsolutePath());

    assertThatThrownBy(() -> storage.getOutputDirectory(sessionId, "720p"))
        .isInstanceOf(InvalidSegmentPathException.class);
    assertThat(externalSession.resolve("720p")).doesNotExist();
  }

  @Test
  @DisplayName("Should delete a legacy session symlink without following its target")
  void shouldDeleteLegacySessionSymlinkWithoutFollowingItsTarget() throws IOException {
    var sessionId = UUID.randomUUID();
    var externalSession = Files.createDirectories(externalDir.resolve(sessionId.toString()));
    var externalSegment = Files.writeString(externalSession.resolve("segment0.ts"), "external");
    var sessionLink = tempDir.resolve(sessionId.toString());
    Files.createSymbolicLink(sessionLink, externalSession.toAbsolutePath());

    storage.deleteSession(sessionId);

    assertThat(Files.exists(sessionLink, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
    assertThat(externalSegment).exists().hasContent("external");
  }

  @Test
  @DisplayName("Should reject deletion after managed base is replaced by an external symlink")
  void shouldRejectDeletionAfterManagedBaseIsReplacedByExternalSymlink() throws IOException {
    var managedBase = Files.createDirectories(tempDir.resolve("managed"));
    storage = new LocalSegmentStorage(managedBase);
    var sessionId = UUID.randomUUID();
    var externalSession = Files.createDirectories(externalDir.resolve(sessionId.toString()));
    var externalSegment = Files.writeString(externalSession.resolve("segment0.ts"), "external");
    Files.delete(managedBase);
    Files.createSymbolicLink(managedBase, externalDir.toAbsolutePath());

    assertThatThrownBy(() -> storage.deleteSession(sessionId))
        .isInstanceOf(InvalidSegmentPathException.class);
    assertThat(externalSegment).exists();
  }

  @Test
  @DisplayName("Should accept a configured base symlink when storage is initialized")
  void shouldAcceptConfiguredBaseSymlinkWhenStorageIsInitialized() throws IOException {
    var configuredTarget = Files.createDirectories(externalDir.resolve("configured-storage"));
    var configuredBase = tempDir.resolve("configured-storage");
    Files.createSymbolicLink(configuredBase, configuredTarget.toAbsolutePath());
    storage = new LocalSegmentStorage(configuredBase);

    var outputDirectory = storage.getOutputDirectory(UUID.randomUUID());

    assertThat(outputDirectory.toRealPath()).startsWith(configuredTarget.toRealPath());
  }

  @Test
  @DisplayName("Should retry generation discard after configured base identity is restored")
  void shouldRetryGenerationDiscardAfterConfiguredBaseIdentityIsRestored() throws IOException {
    var configuredTarget = Files.createDirectories(externalDir.resolve("configured-storage"));
    var replacementTarget = Files.createDirectories(externalDir.resolve("replacement-storage"));
    var configuredBase = tempDir.resolve("configured-storage");
    Files.createSymbolicLink(configuredBase, configuredTarget.toAbsolutePath());
    storage = new LocalSegmentStorage(configuredBase);
    var generation =
        writeGeneration(
            UUID.randomUUID(), new TranscodeJobRef(UUID.randomUUID(), 1), "staged-segment");
    var generationRelativePath = configuredBase.relativize(generation.outputDirectory());
    Files.createDirectories(replacementTarget.resolve(generationRelativePath));
    Files.delete(configuredBase);
    Files.createSymbolicLink(configuredBase, replacementTarget.toAbsolutePath());

    assertThatThrownBy(() -> storage.discard(generation))
        .isInstanceOf(InvalidSegmentPathException.class);
    Files.delete(configuredBase);
    Files.createSymbolicLink(configuredBase, configuredTarget.toAbsolutePath());

    storage.discard(generation);

    assertThat(generation.outputDirectory()).doesNotExist();
  }

  @Test
  @DisplayName("Should reject session discovery through an external staging symlink")
  void shouldRejectSessionDiscoveryThroughExternalStagingSymlink() throws IOException {
    Files.createDirectories(externalDir.resolve(UUID.randomUUID().toString()));
    Files.createSymbolicLink(tempDir.resolve(".staging"), externalDir.toAbsolutePath());

    assertThatThrownBy(storage::snapshotStoredSessionIds)
        .isInstanceOf(InvalidSegmentPathException.class);
  }

  @Test
  @DisplayName("Should reject deletion of a session symlink below an escaped staging parent")
  void shouldRejectDeletionOfSessionSymlinkBelowEscapedStagingParent() throws IOException {
    var sessionId = UUID.randomUUID();
    var externalStaging = Files.createDirectories(externalDir.resolve("staging"));
    var externalTarget = Files.createDirectories(externalDir.resolve("target"));
    var externalSessionLink = externalStaging.resolve(sessionId.toString());
    Files.createSymbolicLink(externalSessionLink, externalTarget.toAbsolutePath());
    Files.createSymbolicLink(tempDir.resolve(".staging"), externalStaging.toAbsolutePath());

    assertThatThrownBy(() -> storage.deleteSession(sessionId))
        .isInstanceOf(InvalidSegmentPathException.class);
    assertThat(Files.exists(externalSessionLink, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isTrue();
  }

  @Test
  @DisplayName("Should reject a rendition directory symlink outside managed storage")
  void shouldRejectRenditionDirectorySymlinkOutsideManagedStorage() throws IOException {
    var generation =
        storage.prepareGeneration(UUID.randomUUID(), new TranscodeJobRef(UUID.randomUUID(), 1));
    var externalRendition = Files.createDirectories(externalDir.resolve("720p"));
    Files.createSymbolicLink(
        generation.outputDirectory().resolve("720p"), externalRendition.toAbsolutePath());

    assertThatThrownBy(() -> storage.prepareRenditionDirectory(generation, "720p"))
        .isInstanceOf(InvalidSegmentPathException.class);
  }

  @Test
  @DisplayName("Should reject a staged artifact symlink outside managed storage")
  void shouldRejectStagedArtifactSymlinkOutsideManagedStorage() throws IOException {
    var generation =
        storage.prepareGeneration(UUID.randomUUID(), new TranscodeJobRef(UUID.randomUUID(), 1));
    var renditionDirectory = storage.prepareRenditionDirectory(generation, "720p");
    var externalManifest = Files.writeString(externalDir.resolve("stream.m3u8"), "external");
    Files.createSymbolicLink(
        renditionDirectory.resolve("stream.m3u8"), externalManifest.toAbsolutePath());

    assertThatThrownBy(() -> storage.readStagedArtifact(generation, "720p/stream.m3u8"))
        .isInstanceOf(InvalidSegmentPathException.class);
    assertThatThrownBy(() -> storage.isStagedArtifactNonEmpty(generation, "720p/stream.m3u8"))
        .isInstanceOf(InvalidSegmentPathException.class);
  }

  @Test
  @DisplayName("Should reject a staged directory as an artifact")
  void shouldRejectStagedDirectoryAsArtifact() {
    var generation =
        storage.prepareGeneration(UUID.randomUUID(), new TranscodeJobRef(UUID.randomUUID(), 1));
    storage.prepareRenditionDirectory(generation, "720p");

    assertThatThrownBy(() -> storage.readStagedArtifact(generation, "720p"))
        .isInstanceOf(InvalidSegmentPathException.class);
    assertThatThrownBy(() -> storage.isStagedArtifactNonEmpty(generation, "720p"))
        .isInstanceOf(InvalidSegmentPathException.class);
  }

  @Test
  @DisplayName("Should reject a staged artifact that exceeds the inspection limit")
  void shouldRejectStagedArtifactThatExceedsInspectionLimit() throws IOException {
    var generation =
        storage.prepareGeneration(UUID.randomUUID(), new TranscodeJobRef(UUID.randomUUID(), 1));
    Files.write(generation.outputDirectory().resolve("stream.m3u8"), new byte[1_048_577]);

    assertThatThrownBy(() -> storage.readStagedArtifact(generation, "stream.m3u8"))
        .isInstanceOf(TranscodeException.class)
        .hasMessage("Staged artifact exceeds inspection limit");
  }

  @Test
  @DisplayName("Should reject cleanup and reads through a superseded prepared generation")
  void shouldRejectCleanupAndReadsThroughSupersededPreparedGeneration() {
    var sessionId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var superseded = storage.prepareGeneration(sessionId, new TranscodeJobRef(jobId, 1));
    var current = storage.prepareGeneration(sessionId, new TranscodeJobRef(jobId, 2));

    assertThatThrownBy(() -> storage.discard(superseded)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> storage.readStagedArtifact(superseded, "stream.m3u8"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(current.outputDirectory()).exists();
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

  @Test
  @DisplayName("Should reject a regular file at the generation root")
  void shouldRejectRegularFileAtGenerationRoot() throws IOException {
    var sessionId = UUID.randomUUID();
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);
    var generationRoot =
        tempDir
            .resolve(".staging")
            .resolve(sessionId.toString())
            .resolve(jobRef.jobId() + "-" + jobRef.generation());
    Files.createDirectories(generationRoot.getParent());
    Files.createFile(generationRoot);

    assertThatThrownBy(() -> storage.prepareGeneration(sessionId, jobRef))
        .isInstanceOf(IllegalStateException.class);
    assertThat(generationRoot).isRegularFile();
  }

  @Test
  @DisplayName("Should report no stored sessions before the staging layout exists")
  void shouldReportNoStoredSessionsBeforeStagingLayoutExists() {
    assertThat(storage.snapshotStoredSessionIds()).isEmpty();
  }

  @Test
  @DisplayName("Should reject traversal while waiting for a segment")
  void shouldRejectTraversalWhileWaitingForSegment() {
    var sessionId = UUID.randomUUID();
    var timeout = Duration.ofMillis(1);

    assertThatThrownBy(() -> storage.waitForSegment(sessionId, "../segment0.ts", timeout))
        .isInstanceOf(InvalidSegmentPathException.class);
  }

  @Test
  @DisplayName("Should report cached session verification failure when base disappears")
  void shouldReportCachedSessionVerificationFailureWhenBaseDisappears() throws IOException {
    var managedBase = Files.createDirectory(tempDir.resolve("managed"));
    storage = new LocalSegmentStorage(managedBase);
    var sessionId = UUID.randomUUID();
    Files.delete(storage.getOutputDirectory(sessionId));
    Files.delete(managedBase);

    assertThatThrownBy(() -> storage.getOutputDirectory(sessionId))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessage("Failed to verify session directory");
  }

  @Test
  @DisplayName("Should report legacy rendition creation failure when its path is a file")
  void shouldReportLegacyRenditionCreationFailureWhenItsPathIsFile() throws IOException {
    var sessionId = UUID.randomUUID();
    Files.createFile(storage.getOutputDirectory(sessionId).resolve("720p"));

    assertThatThrownBy(() -> storage.getOutputDirectory(sessionId, "720p"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessage("Failed to create variant directory");
  }

  @Test
  @DisplayName("Should report generation preparation failure when base becomes a file")
  void shouldReportGenerationPreparationFailureWhenBaseBecomesFile() throws IOException {
    var managedBase = Files.createDirectory(tempDir.resolve("managed"));
    storage = new LocalSegmentStorage(managedBase);
    var sessionId = UUID.randomUUID();
    var jobRef = new TranscodeJobRef(UUID.randomUUID(), 1);
    Files.delete(managedBase);
    Files.createFile(managedBase);

    assertThatThrownBy(() -> storage.prepareGeneration(sessionId, jobRef))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessage("Failed to prepare segment generation");
  }

  @Test
  @DisplayName("Should report rendition preparation failure when its path is a file")
  void shouldReportRenditionPreparationFailureWhenItsPathIsFile() throws IOException {
    var generation =
        storage.prepareGeneration(UUID.randomUUID(), new TranscodeJobRef(UUID.randomUUID(), 1));
    Files.createFile(generation.outputDirectory().resolve("720p"));

    assertThatThrownBy(() -> storage.prepareRenditionDirectory(generation, "720p"))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessage("Failed to prepare rendition directory");
  }

  @Test
  @DisplayName("Should report stored session discovery failure when base becomes a file")
  void shouldReportStoredSessionDiscoveryFailureWhenBaseBecomesFile() throws IOException {
    var managedBase = Files.createDirectory(tempDir.resolve("managed"));
    storage = new LocalSegmentStorage(managedBase);
    Files.delete(managedBase);
    Files.createFile(managedBase);

    assertThatThrownBy(storage::snapshotStoredSessionIds)
        .isInstanceOf(UncheckedIOException.class)
        .hasMessage("Failed to discover stored stream sessions");
  }

  @Test
  @DisplayName("Should report session deletion verification failure when base disappears")
  void shouldReportSessionDeletionVerificationFailureWhenBaseDisappears() throws IOException {
    var managedBase = Files.createDirectory(tempDir.resolve("managed"));
    storage = new LocalSegmentStorage(managedBase);
    var sessionId = UUID.randomUUID();
    Files.delete(managedBase);

    assertThatThrownBy(() -> storage.deleteSession(sessionId))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessage("Failed to verify session storage");
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

  private static final class ObservableSegmentStorage extends LocalSegmentStorage {

    private final CountDownLatch firstSegmentCheck = new CountDownLatch(1);
    private final CountDownLatch segmentChecksAllowed = new CountDownLatch(1);

    private ObservableSegmentStorage(Path baseDir) {
      super(baseDir);
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      var exists = super.segmentExists(sessionId, segmentName);
      firstSegmentCheck.countDown();
      awaitSegmentChecksAllowed();
      return exists;
    }

    private boolean awaitFirstSegmentCheck() throws InterruptedException {
      return firstSegmentCheck.await(1, TimeUnit.SECONDS);
    }

    private void allowSegmentChecks() {
      segmentChecksAllowed.countDown();
    }

    private void awaitSegmentChecksAllowed() {
      try {
        if (!segmentChecksAllowed.await(1, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Segment checks were not released");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while awaiting segment checks", exception);
      }
    }
  }
}
