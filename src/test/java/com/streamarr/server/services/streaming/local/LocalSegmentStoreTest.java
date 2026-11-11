package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Feature;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.exceptions.FileShrankException;
import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.fixtures.StoredSegments;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
  private static final String VARIANT_MEDIA_SEGMENT = "720p/segment0.m4s";
  private static final int CONTENDERS = 8;
  private static final int RACE_ROUNDS = 25;
  // Without the per-name lock, only some rounds of a media race interleave a check and a move.
  private static final int MEDIA_RACE_ROUNDS = 100;

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
    Files.write(outputDir.resolve("segment0.m4s"), expectedBytes);

    var result = StoredSegments.bytes(store, sessionId, "segment0.m4s");

    assertThat(result).isEqualTo(expectedBytes);
  }

  @Test
  @DisplayName("Should find no segment when opening a name that was never published")
  void shouldFindNoSegmentWhenOpeningNameThatWasNeverPublished() {
    var sessionId = UUID.randomUUID();
    store.getOutputDirectory(sessionId);

    assertThat(store.openSegment(sessionId, "missing.m4s")).isEmpty();
  }

  @Test
  @DisplayName("Should find no segment when opening one for a session without an output directory")
  void shouldFindNoSegmentWhenOpeningOneForSessionWithoutOutputDirectory() {
    assertThat(store.openSegment(UUID.randomUUID(), "segment0.m4s")).isEmpty();
  }

  @Test
  @DisplayName("Should read each stream of an opened segment from its first byte")
  void shouldReadEachStreamOfOpenedSegmentFromItsFirstByte() throws IOException {
    var sessionId = UUID.randomUUID();
    store.storeSegment(sessionId, "segment0.m4s", new byte[] {7, 8, 9});

    try (var opened = store.openSegment(sessionId, "segment0.m4s").orElseThrow();
        var first = opened.newInputStream();
        var second = opened.newInputStream()) {
      assertThat(first.read()).isEqualTo(7);
      assertThat(second).hasBinaryContent(new byte[] {7, 8, 9});
      assertThat(first.readAllBytes()).containsExactly(8, 9);
      assertThat(first.read()).isEqualTo(-1);
    }
  }

  @Test
  @DisplayName("Should fail the read past the remaining byte when an opened segment shrinks")
  void shouldFailTheReadPastTheRemainingByteWhenAnOpenedSegmentShrinks() throws IOException {
    var sessionId = UUID.randomUUID();
    var name = "segment0.m4s";
    store.storeSegment(sessionId, name, new byte[] {-1, 2, 3, 4});
    var path = store.getOutputDirectory(sessionId).resolve(name);

    try (var opened = store.openSegment(sessionId, name).orElseThrow();
        var stream = opened.newInputStream();
        var writer = FileChannel.open(path, StandardOpenOption.WRITE)) {
      writer.truncate(1);

      assertThat(stream.read()).isEqualTo(255);
      assertThatThrownBy(stream::read).isInstanceOf(FileShrankException.class);
    }
  }

  @Test
  @DisplayName("Should read from the byte after the skipped ones when a stream skips ahead")
  void shouldReadFromByteAfterSkippedOnesWhenStreamSkipsAhead() throws IOException {
    var sessionId = UUID.randomUUID();
    store.storeSegment(sessionId, "segment0.m4s", new byte[] {7, 8, 9, 10});

    try (var opened = store.openSegment(sessionId, "segment0.m4s").orElseThrow();
        var stream = opened.newInputStream()) {
      assertThat(stream.skip(-1)).isZero();
      assertThat(stream.skip(2)).isEqualTo(2);
      assertThat(stream.read()).isEqualTo(9);
    }
  }

  @Test
  @DisplayName("Should skip ahead without reading the stored bytes when a stream skips")
  void shouldSkipAheadWithoutReadingStoredBytesWhenStreamSkips() throws IOException {
    var sessionId = UUID.randomUUID();
    store.storeSegment(sessionId, "segment0.m4s", new byte[] {7, 8, 9, 10});

    try (var opened = store.openSegment(sessionId, "segment0.m4s").orElseThrow();
        var stream = opened.newInputStream()) {
      // With the file emptied under the opened segment, a skip that read the bytes it passes
      // over would find none and skip nothing.
      Files.write(store.getOutputDirectory(sessionId).resolve("segment0.m4s"), new byte[0]);

      assertThat(stream.skip(3)).isEqualTo(3);
    }
  }

  @Test
  @DisplayName("Should skip only the remaining bytes when a stream skips past the segment's end")
  void shouldSkipOnlyRemainingBytesWhenStreamSkipsPastSegmentEnd() throws IOException {
    var sessionId = UUID.randomUUID();
    store.storeSegment(sessionId, "segment0.m4s", new byte[] {7, 8, 9, 10});

    try (var opened = store.openSegment(sessionId, "segment0.m4s").orElseThrow();
        var stream = opened.newInputStream()) {
      assertThat(stream.skip(1)).isEqualTo(1);
      assertThat(stream.skip(Long.MAX_VALUE)).isEqualTo(3);
      assertThat(stream.read()).isEqualTo(-1);
    }
  }

  @Test
  @DisplayName("Should keep an opened segment readable when its session is deleted")
  void shouldKeepOpenedSegmentReadableWhenItsSessionIsDeleted() {
    var sessionId = UUID.randomUUID();
    var segmentData = "remote segment".getBytes();
    store.storeSegment(sessionId, "segment0.m4s", segmentData);

    try (var opened = store.openSegment(sessionId, "segment0.m4s").orElseThrow()) {
      store.deleteSession(sessionId);

      assertThat(opened.length()).isEqualTo(segmentData.length);
      assertThat(opened.newInputStream()).hasBinaryContent(segmentData);
    }
  }

  @Test
  @DisplayName("Should delete directory and contents when session is deleted")
  void shouldDeleteDirectoryAndContentsWhenSessionIsDeleted() throws IOException {
    var sessionId = UUID.randomUUID();
    var outputDir = store.getOutputDirectory(sessionId);
    Files.write(outputDir.resolve("segment0.m4s"), "data".getBytes());

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
    assertThat(store.segmentExists(sessionId, "segment0.m4s")).isFalse();
  }

  @Test
  @DisplayName("Should reject segment name when path traversal is attempted")
  void shouldRejectSegmentNameWhenPathTraversalIsAttempted() {
    var sessionId = UUID.randomUUID();
    store.getOutputDirectory(sessionId);

    assertThatThrownBy(() -> store.openSegment(sessionId, "../../etc/passwd.m4s"))
        .isInstanceOf(InvalidSegmentPathException.class);
  }

  @Test
  @DisplayName("Should read segment when name includes valid subdirectory")
  void shouldReadSegmentWhenNameIncludesValidSubdirectory() throws IOException {
    var sessionId = UUID.randomUUID();
    var outputDir = store.getOutputDirectory(sessionId);
    var variantDir = outputDir.resolve("720p");
    Files.createDirectories(variantDir);
    Files.write(variantDir.resolve("segment0.m4s"), "data".getBytes());

    var result = StoredSegments.bytes(store, sessionId, "720p/segment0.m4s");

    assertThat(result).isEqualTo("data".getBytes());
  }

  @Test
  @DisplayName("Should store a complete segment when a remote upload is published")
  void shouldStoreCompleteSegmentWhenRemoteUploadIsPublished() {
    var sessionId = UUID.randomUUID();
    var segmentData = "remote segment".getBytes();
    var segmentName = "720p/segment0.m4s";

    assertThat(store.segmentExists(sessionId, segmentName)).isFalse();

    store.storeSegment(sessionId, segmentName, segmentData);

    assertThat(store.segmentExists(sessionId, segmentName)).isTrue();
    assertThat(StoredSegments.bytes(store, sessionId, segmentName)).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should keep the stored media segment when its name is published again")
  void shouldKeepStoredMediaSegmentWhenItsNameIsPublishedAgain() {
    var sessionId = UUID.randomUUID();
    var stored = "moof mdat from the first job attempt".getBytes();
    store.storeSegment(sessionId, "720p/segment0.m4s", stored);

    var publication =
        store.storeSegment(sessionId, "720p/segment0.m4s", "moof mdat from a later one".getBytes());

    assertThat(publication).isEqualTo(SegmentPublication.PUBLISHED);
    assertThat(StoredSegments.bytes(store, sessionId, "720p/segment0.m4s")).isEqualTo(stored);
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
    assertThat(StoredSegments.bytes(store, sessionId, "720p/init.mp4")).isEqualTo(stored);
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
    assertThat(StoredSegments.bytes(store, sessionId, "init.mp4")).isEqualTo(stored);
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
      assertThat(StoredSegments.bytes(linklessStore, sessionId, VARIANT_INITIALIZATION_SEGMENT))
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
      assertThat(StoredSegments.bytes(store, sessionId, VARIANT_INITIALIZATION_SEGMENT))
          .isEqualTo(contenderInitialization(stored.getFirst()));
    }
  }

  private static List<Integer> contendersStoredByConcurrentPublication(
      SegmentStore store, UUID sessionId) {
    var prepared =
        IntStream.range(0, CONTENDERS)
            .mapToObj(
                contender ->
                    appended(
                        store.prepareSegment(sessionId, VARIANT_INITIALIZATION_SEGMENT),
                        contenderInitialization(contender)))
            .toList();
    var publications = afterConcurrentPublication(prepared, Function.identity());
    return IntStream.range(0, CONTENDERS)
        .filter(contender -> publications.get(contender) == SegmentPublication.PUBLISHED)
        .boxed()
        .toList();
  }

  @Test
  @DisplayName(
      "Should never change a stored media segment when differing ones are published concurrently")
  void shouldNeverChangeStoredMediaSegmentWhenDifferingOnesArePublishedConcurrently() {
    for (var round = 0; round < MEDIA_RACE_ROUNDS; round++) {
      var sessionId = UUID.randomUUID();

      var readsAfterPublication = readsAfterConcurrentMediaPublication(sessionId);

      var stored = StoredSegments.bytes(store, sessionId, VARIANT_MEDIA_SEGMENT);
      assertThat(readsAfterPublication)
          .as("bytes each contender read after its own publication in round %s", round)
          .allSatisfy(read -> assertThat(read).isEqualTo(stored));
    }
  }

  // Each contender reads the stored segment as soon as its own publication returns.
  private List<byte[]> readsAfterConcurrentMediaPublication(UUID sessionId) {
    var prepared =
        IntStream.range(0, CONTENDERS)
            .mapToObj(
                contender ->
                    appended(
                        store.prepareSegment(sessionId, VARIANT_MEDIA_SEGMENT),
                        ("moof mdat from encoder " + contender).getBytes()))
            .toList();
    return afterConcurrentPublication(
        prepared, _ -> StoredSegments.bytes(store, sessionId, VARIANT_MEDIA_SEGMENT));
  }

  // Publishes every prepared segment at once and answers, in order, what each contender saw
  // straight after its own publication returned.
  private static <T> List<T> afterConcurrentPublication(
      List<SegmentStore.PreparedSegment> prepared,
      Function<SegmentPublication, T> afterPublication) {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var contenders =
          prepared.stream()
              .map(
                  segment ->
                      CompletableFuture.supplyAsync(
                          () -> afterPublication.apply(publishAfter(start, segment)), executor))
              .toList();
      start.countDown();
      return contenders.stream()
          .map(contender -> contender.orTimeout(5, TimeUnit.SECONDS).join())
          .toList();
    }
  }

  private static SegmentStore.PreparedSegment appended(
      SegmentStore.PreparedSegment prepared, byte[] data) {
    prepared.append(ByteBuffer.wrap(data));
    return prepared;
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
  @DisplayName("Should store the appended chunks in order when a prepared segment is published")
  void shouldStoreAppendedChunksInOrderWhenPreparedSegmentIsPublished() {
    var sessionId = UUID.randomUUID();

    try (var prepared = store.prepareSegment(sessionId, "720p/segment0.m4s")) {
      prepared.append(ByteBuffer.wrap("remote ".getBytes()));
      prepared.append(ByteBuffer.wrap("segment".getBytes()));
      assertThat(store.segmentExists(sessionId, "720p/segment0.m4s")).isFalse();

      prepared.publish();
    }

    assertThat(StoredSegments.bytes(store, sessionId, "720p/segment0.m4s"))
        .isEqualTo("remote segment".getBytes());
    assertThat(tempDir)
        .as("no temporary upload file remains beside the session's folder")
        .isDirectoryNotContaining("glob:**.tmp");
  }

  @Test
  @DisplayName("Should not expose a prepared segment when publication has not occurred")
  void shouldNotExposePreparedSegmentWhenPublicationHasNotOccurred() {
    var sessionId = UUID.randomUUID();
    var segmentData = "remote segment".getBytes();

    try (var prepared =
        appended(store.prepareSegment(sessionId, "720p/segment0.m4s"), segmentData)) {
      assertThat(tempDir.resolve(sessionId.toString())).doesNotExist();
      assertThat(store.segmentExists(sessionId, "720p/segment0.m4s")).isFalse();

      prepared.publish();
    }

    assertThat(StoredSegments.bytes(store, sessionId, "720p/segment0.m4s")).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should discard a prepared segment when it is closed without publication")
  void shouldDiscardPreparedSegmentWhenClosedWithoutPublication() {
    var sessionId = UUID.randomUUID();

    try (var _ =
        appended(
            store.prepareSegment(sessionId, "720p/segment0.m4s"), "remote segment".getBytes())) {
      assertThat(tempDir.resolve(sessionId.toString())).doesNotExist();
    }

    assertThat(tempDir).isEmptyDirectory();
  }

  @Test
  @DisplayName("Should translate cleanup I/O failure when a prepared segment is closed")
  void shouldTranslateCleanupIoFailureWhenPreparedSegmentIsClosed() throws Exception {
    var prepared =
        appended(
            store.prepareSegment(UUID.randomUUID(), "720p/segment0.m4s"),
            "remote segment".getBytes());
    Path temporary;
    try (var files = Files.list(tempDir)) {
      temporary = files.findFirst().orElseThrow();
    }
    Files.delete(temporary);
    Files.createDirectory(temporary);
    Files.writeString(temporary.resolve("undeletable-child"), "data");

    assertThatThrownBy(prepared::close)
        .isInstanceOf(UncheckedIOException.class)
        .hasMessage("Failed to clean up segment upload: 720p/segment0.m4s")
        .cause()
        .isInstanceOf(IOException.class);
  }

  @Test
  @DisplayName(
      "Should escalate an I/O failure when a media segment's stored state cannot be determined")
  void shouldEscalateIoFailureWhenMediaSegmentStoredStateCannotBeDetermined() throws Exception {
    var sessionId = UUID.randomUUID();
    plantUnresolvableLink(sessionId, "segment0.m4s");

    assertThatThrownBy(() -> store.storeSegment(sessionId, "segment0.m4s", new byte[] {0x47}))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("segment0.m4s");
  }

  @Test
  @DisplayName(
      "Should escalate an I/O failure when an initialization segment's stored state cannot be determined")
  void shouldEscalateIoFailureWhenInitializationSegmentStoredStateCannotBeDetermined()
      throws Exception {
    var sessionId = UUID.randomUUID();
    plantUnresolvableLink(sessionId, "init.mp4");

    assertThatThrownBy(() -> store.storeSegment(sessionId, "init.mp4", new byte[] {0x47}))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("init.mp4");
  }

  // A soft link that names itself: looking the name up fails, so the file system can say neither
  // that a segment is stored there nor that none is.
  private void plantUnresolvableLink(UUID sessionId, String segmentName) throws IOException {
    var link = store.getOutputDirectory(sessionId).resolve(segmentName);
    Files.createSymbolicLink(link, link.getFileName());
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
    assertThatThrownBy(() -> store.storeSegment(sessionId, "segment0.m4s", new byte[] {0x47}))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("session directory");
  }

  @Test
  @DisplayName("Should reject a stored segment when its path escapes the session directory")
  void shouldRejectStoredSegmentWhenPathEscapesSessionDirectory() {
    var sessionId = UUID.randomUUID();
    var segmentData = "data".getBytes();

    assertThatThrownBy(() -> store.storeSegment(sessionId, "../../escaped.m4s", segmentData))
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
