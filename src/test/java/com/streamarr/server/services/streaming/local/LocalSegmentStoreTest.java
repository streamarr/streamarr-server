package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Feature;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Local Segment Store Tests")
class LocalSegmentStoreTest {

  private static final String VARIANT_INITIALIZATION_SEGMENT = "720p/init.mp4";
  private static final int CONTENDERS = 8;
  private static final int RACE_ROUNDS = 25;

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
  @DisplayName("Should report a missing segment when session has no output directory")
  void shouldReportMissingSegmentWhenSessionHasNoOutputDirectory() {
    var sessionId = UUID.randomUUID();

    // In remote mode nothing creates the directory until a worker's first upload; the miss must
    // read as "not yet present", never as an error.
    assertThat(store.segmentExists(sessionId, "segment0.ts")).isFalse();
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
  @DisplayName("Should store a complete segment when a remote upload is published")
  void shouldStoreCompleteSegmentWhenRemoteUploadIsPublished() {
    var sessionId = UUID.randomUUID();
    var segmentData = "remote segment".getBytes();
    var segmentName = "720p/segment0.ts";

    assertThat(store.segmentExists(sessionId, segmentName)).isFalse();

    store.storeSegment(sessionId, segmentName, segmentData);

    assertThat(store.segmentExists(sessionId, segmentName)).isTrue();
    assertThat(store.readSegment(sessionId, segmentName)).isEqualTo(segmentData);
  }

  @Test
  @DisplayName(
      "Should keep the stored initialization segment when a different one is published for the variant")
  void shouldKeepStoredInitializationSegmentWhenDifferentOneIsPublishedForVariant() {
    var sessionId = UUID.randomUUID();
    var stored = "ftyp moov from encoder A".getBytes();
    store.storeSegment(sessionId, "720p/init.mp4", stored);

    var publication =
        store.storeSegment(sessionId, "720p/init.mp4", "ftyp moov from encoder B".getBytes());

    assertThat(publication).isEqualTo(SegmentPublication.INITIALIZATION_SEGMENT_DIFFERS);
    assertThat(store.readSegment(sessionId, "720p/init.mp4")).isEqualTo(stored);
  }

  @Test
  @DisplayName(
      "Should publish an initialization segment when it matches the one stored for the variant")
  void shouldPublishInitializationSegmentWhenItMatchesOneStoredForVariant() {
    var sessionId = UUID.randomUUID();
    var stored = "ftyp moov from encoder A".getBytes();
    store.storeSegment(sessionId, "init.mp4", stored);

    var publication = store.storeSegment(sessionId, "init.mp4", stored.clone());

    assertThat(publication).isEqualTo(SegmentPublication.PUBLISHED);
    assertThat(store.readSegment(sessionId, "init.mp4")).isEqualTo(stored);
  }

  @Test
  @DisplayName(
      "Should store exactly one initialization segment when differing ones are published concurrently")
  void shouldStoreExactlyOneInitializationSegmentWhenDifferingOnesArePublishedConcurrently() {
    assertExactlyOneContenderStoredPerRace(store);
  }

  @Test
  @DisplayName(
      "Should store the first initialization segment when the segment volume cannot create hard links")
  void shouldStoreFirstInitializationSegmentWhenSegmentVolumeCannotCreateHardLinks()
      throws IOException {
    try (var volume = volumeWithoutHardLinks()) {
      var linklessStore = new LocalSegmentStore(volume.getPath("/segments"));
      var sessionId = UUID.randomUUID();
      var initialization = "ftyp moov from encoder A".getBytes();

      var publication =
          linklessStore.storeSegment(sessionId, VARIANT_INITIALIZATION_SEGMENT, initialization);

      assertThat(publication).isEqualTo(SegmentPublication.PUBLISHED);
      assertThat(linklessStore.readSegment(sessionId, VARIANT_INITIALIZATION_SEGMENT))
          .isEqualTo(initialization);
    }
  }

  @Test
  @DisplayName(
      "Should store exactly one initialization segment when differing ones race on a volume that cannot create hard links")
  void shouldStoreExactlyOneInitializationSegmentWhenDifferingOnesRaceOnVolumeWithoutHardLinks()
      throws IOException {
    try (var volume = volumeWithoutHardLinks()) {
      assertExactlyOneContenderStoredPerRace(new LocalSegmentStore(volume.getPath("/segments")));
    }
  }

  /** Like exFAT and some network mounts: renames work, hard links do not. */
  private static FileSystem volumeWithoutHardLinks() {
    return Jimfs.newFileSystem(
        Configuration.unix().toBuilder()
            .setSupportedFeatures(
                Feature.SYMBOLIC_LINKS, Feature.SECURE_DIRECTORY_STREAM, Feature.FILE_CHANNEL)
            .build());
  }

  private static void assertExactlyOneContenderStoredPerRace(SegmentStore store) {
    for (var round = 0; round < RACE_ROUNDS; round++) {
      var sessionId = UUID.randomUUID();

      var stored = contendersStoredByConcurrentPublication(store, sessionId);

      assertThat(stored).as("contenders stored in round %s", round).hasSize(1);
      assertThat(store.readSegment(sessionId, VARIANT_INITIALIZATION_SEGMENT))
          .isEqualTo(contenderInitialization(stored.getFirst()));
    }
  }

  private static List<Integer> contendersStoredByConcurrentPublication(
      SegmentStore store, UUID sessionId) {
    var start = new CountDownLatch(1);
    var prepared =
        IntStream.range(0, CONTENDERS)
            .mapToObj(
                contender ->
                    store.prepareSegment(
                        sessionId,
                        VARIANT_INITIALIZATION_SEGMENT,
                        contenderInitialization(contender)))
            .toList();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publications =
          prepared.stream()
              .map(
                  segment ->
                      CompletableFuture.supplyAsync(() -> publishAfter(start, segment), executor))
              .toList();
      start.countDown();
      return IntStream.range(0, CONTENDERS)
          .filter(
              contender ->
                  publications.get(contender).orTimeout(5, TimeUnit.SECONDS).join()
                      == SegmentPublication.PUBLISHED)
          .boxed()
          .toList();
    }
  }

  private static byte[] contenderInitialization(int contender) {
    return ("encoder " + contender).getBytes();
  }

  private static SegmentPublication publishAfter(
      CountDownLatch start, SegmentStore.PreparedSegment prepared) {
    try (prepared) {
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for the race to start");
      }

      return prepared.publish();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  @Test
  @DisplayName("Should not expose a prepared segment when publication has not occurred")
  void shouldNotExposePreparedSegmentWhenPublicationHasNotOccurred() {
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
  @DisplayName("Should translate cleanup I/O failure when a prepared segment is closed")
  void shouldTranslateCleanupIoFailureWhenPreparedSegmentIsClosed() throws Exception {
    var prepared =
        store.prepareSegment(UUID.randomUUID(), "720p/segment0.ts", "remote segment".getBytes());
    Path temporary;
    try (var files = Files.list(tempDir)) {
      temporary = files.findFirst().orElseThrow();
    }
    Files.delete(temporary);
    Files.createDirectory(temporary);
    Files.writeString(temporary.resolve("undeletable-child"), "data");

    assertThatThrownBy(prepared::close)
        .isInstanceOf(UncheckedIOException.class)
        .hasMessage("Failed to clean up segment upload: 720p/segment0.ts")
        .cause()
        .isInstanceOf(IOException.class);
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
  @DisplayName("Should escalate an I/O failure when the session path is not a directory")
  void shouldEscalateIoFailureWhenSessionPathIsNotDirectory() throws Exception {
    var sessionId = UUID.randomUUID();
    // A plain file squatting on the session directory path makes every write fail as real IO.
    Files.createDirectories(tempDir);
    Files.writeString(tempDir.resolve(sessionId.toString()), "not a directory");

    // Deliberately NOT TranscodeException: the delivery loop swallows that type as a destroy
    // race, so a genuine storage failure must surface as UncheckedIOException instead.
    assertThatThrownBy(() -> store.storeSegment(sessionId, "segment0.ts", new byte[] {0x47}))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("session directory");
  }

  @Test
  @DisplayName("Should reject a stored segment when its path escapes the session directory")
  void shouldRejectStoredSegmentWhenPathEscapesSessionDirectory() {
    var sessionId = UUID.randomUUID();
    var segmentData = "data".getBytes();

    assertThatThrownBy(() -> store.storeSegment(sessionId, "../../escaped.ts", segmentData))
        .isInstanceOf(InvalidSegmentPathException.class);
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
