package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Segment Folder Leftovers Tests")
class SegmentFolderLeftoversTest {

  private static final byte[] SEGMENT = "segment".getBytes();
  private static final String MARK_NAME = ".streamarr-segment-folder";

  @TempDir Path segmentFolder;
  @TempDir Path outside;

  @Test
  @DisplayName("Should delete a stream session's directory when an earlier store left it")
  void shouldDeleteAStreamSessionsDirectoryWhenAnEarlierStoreLeftIt() {
    leftoversOf(segmentFolder).delete();
    var sessionId = UUID.randomUUID();
    try (var stored = earlierStore().prepareSegment(sessionId, "720p/0.m4s", SEGMENT)) {
      stored.publish();
    }

    leftoversOf(segmentFolder).delete();

    assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
  }

  @Test
  @DisplayName("Should delete an unpublished upload when an earlier store left it")
  void shouldDeleteAnUnpublishedUploadWhenAnEarlierStoreLeftIt() {
    leftoversOf(segmentFolder).delete();
    // Still open: the store's process ended before it published or discarded the upload.
    try (var _ = earlierStore().prepareSegment(UUID.randomUUID(), "720p/0.m4s", SEGMENT)) {
      assertThat(segmentFolder).isDirectoryContaining("glob:**/.upload-*");

      leftoversOf(segmentFolder).delete();

      assertThat(segmentFolder).isDirectoryNotContaining("glob:**/.upload-*");
    }
  }

  @Test
  @DisplayName("Should delete leftovers when it created the segment folder")
  void shouldDeleteLeftoversWhenItCreatedTheSegmentFolder() {
    var created = segmentFolder.resolve("server").resolve("segments");
    leftoversOf(created).delete();
    var sessionId = UUID.randomUUID();
    new LocalSegmentStore(created).getOutputDirectory(sessionId);

    leftoversOf(created).delete();

    assertThat(created.resolve(sessionId.toString())).doesNotExist();
  }

  @Test
  @DisplayName("Should keep everything when the folder held other entries before a store used it")
  void shouldKeepEverythingWhenTheFolderHeldOtherEntriesBeforeAStoreUsedIt() throws IOException {
    var directoryNamedLikeASession =
        Files.createDirectory(segmentFolder.resolve(UUID.randomUUID().toString()));
    var fileInThatDirectory =
        Files.write(directoryNamedLikeASession.resolve("movie.mkv"), "media".getBytes());
    var fileNamedLikeAnUpload =
        Files.write(segmentFolder.resolve(".upload-1.tmp"), "media".getBytes());

    leftoversOf(segmentFolder).delete();
    leftoversOf(segmentFolder).delete();

    assertThat(fileInThatDirectory).exists();
    assertThat(fileNamedLikeAnUpload).exists();
    assertThat(segmentFolder)
        .isDirectoryNotContaining(
            entry ->
                !entry.equals(directoryNamedLikeASession) && !entry.equals(fileNamedLikeAnUpload));
  }

  // A process that ends between creating the mark and writing it leaves an empty mark.
  @Test
  @DisplayName(
      "Should delete leftovers when an earlier process ended before it wrote the folder's mark")
  void shouldDeleteLeftoversWhenAnEarlierProcessEndedBeforeItWroteTheFoldersMark()
      throws IOException {
    Files.createFile(segmentFolder.resolve(MARK_NAME));
    leftoversOf(segmentFolder).delete();
    var sessionId = UUID.randomUUID();
    earlierStore().getOutputDirectory(sessionId);

    leftoversOf(segmentFolder).delete();

    assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
  }

  // Bytes that are no UTF-8 text cannot be read as a folder's path.
  @Test
  @DisplayName("Should delete leftovers when the folder held only a mark that is not text")
  void shouldDeleteLeftoversWhenTheFolderHeldOnlyAMarkThatIsNotText() throws IOException {
    Files.write(segmentFolder.resolve(MARK_NAME), new byte[] {(byte) 0xC3, 0x28});
    new SegmentFolderLeftovers(segmentFolder).delete();
    var sessionId = UUID.randomUUID();
    earlierStore().getOutputDirectory(sessionId);

    new SegmentFolderLeftovers(segmentFolder).delete();

    assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
  }

  // A file of more than Integer.MAX_VALUE bytes cannot be read into memory. This one is sparse,
  // so it takes no space.
  @Test
  @DisplayName("Should delete leftovers when the folder held only a mark too large to read")
  void shouldDeleteLeftoversWhenTheFolderHeldOnlyAMarkTooLargeToRead() throws IOException {
    try (var mark =
        FileChannel.open(
            segmentFolder.resolve(MARK_NAME),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE)) {
      mark.write(ByteBuffer.wrap(new byte[1]), Integer.MAX_VALUE);
    }

    new SegmentFolderLeftovers(segmentFolder).delete();
    var sessionId = UUID.randomUUID();
    earlierStore().getOutputDirectory(sessionId);

    new SegmentFolderLeftovers(segmentFolder).delete();

    assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should keep the file that a soft link in the mark's place points to when it marks the"
          + " folder")
  void shouldKeepTheFileThatASoftLinkInTheMarksPlacePointsToWhenItMarksTheFolder()
      throws IOException {
    var outsideFile = Files.write(outside.resolve("movie.mkv"), "media".getBytes());
    Files.createSymbolicLink(segmentFolder.resolve(MARK_NAME), outsideFile);
    leftoversOf(segmentFolder).delete();
    var sessionId = UUID.randomUUID();
    earlierStore().getOutputDirectory(sessionId);

    leftoversOf(segmentFolder).delete();

    assertThat(outsideFile).hasBinaryContent("media".getBytes());
    assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
  }

  // Copying a segment folder's entries into another folder brings the mark along.
  @Test
  @DisplayName(
      "Should keep everything when the folder's mark was copied from another segment folder")
  void shouldKeepEverythingWhenTheFoldersMarkWasCopiedFromAnotherSegmentFolder()
      throws IOException {
    var otherSegmentFolder = outside.resolve("segments");
    leftoversOf(otherSegmentFolder).delete();
    var directoryNamedLikeASession =
        Files.createDirectory(segmentFolder.resolve(UUID.randomUUID().toString()));
    var fileInThatDirectory =
        Files.write(directoryNamedLikeASession.resolve("movie.mkv"), "media".getBytes());
    Files.copy(otherSegmentFolder.resolve(MARK_NAME), segmentFolder.resolve(MARK_NAME));

    leftoversOf(segmentFolder).delete();

    assertThat(fileInThatDirectory).exists();
  }

  // The two paths have the same length, so the copied mark has the size of the folder's own.
  @Test
  @DisplayName(
      "Should keep everything when the folder's mark was copied from a folder with a path of the"
          + " same length")
  void shouldKeepEverythingWhenTheFoldersMarkWasCopiedFromAFolderWithAPathOfTheSameLength()
      throws IOException {
    var markedFolder = segmentFolder.resolve("a");
    var folderWithTheCopiedMark = segmentFolder.resolve("b");
    new SegmentFolderLeftovers(markedFolder).delete();
    var directoryNamedLikeASession =
        Files.createDirectories(folderWithTheCopiedMark.resolve(UUID.randomUUID().toString()));
    var fileInThatDirectory =
        Files.write(directoryNamedLikeASession.resolve("movie.mkv"), "media".getBytes());
    Files.copy(markedFolder.resolve(MARK_NAME), folderWithTheCopiedMark.resolve(MARK_NAME));

    new SegmentFolderLeftovers(folderWithTheCopiedMark).delete();

    assertThat(fileInThatDirectory).exists();
  }

  @Test
  @DisplayName("Should keep everything when the folder's mark is a soft link")
  void shouldKeepEverythingWhenTheFoldersMarkIsASoftLink() throws IOException {
    leftoversOf(segmentFolder).delete();
    var markOutsideTheFolder =
        Files.move(segmentFolder.resolve(MARK_NAME), outside.resolve(MARK_NAME));
    Files.createSymbolicLink(segmentFolder.resolve(MARK_NAME), markOutsideTheFolder);
    var directoryNamedLikeASession =
        Files.createDirectory(segmentFolder.resolve(UUID.randomUUID().toString()));
    var fileInThatDirectory =
        Files.write(directoryNamedLikeASession.resolve("movie.mkv"), "media".getBytes());

    leftoversOf(segmentFolder).delete();

    assertThat(fileInThatDirectory).exists();
  }

  @Test
  @DisplayName("Should keep each entry that no store writes when it deletes leftovers")
  void shouldKeepEachEntryThatNoStoreWritesWhenItDeletesLeftovers() throws IOException {
    leftoversOf(segmentFolder).delete();
    var otherFile = Files.write(segmentFolder.resolve("notes.txt"), "notes".getBytes());
    var otherDirectory = Files.createDirectory(segmentFolder.resolve("movies"));
    var fileNamedLikeASession =
        Files.write(segmentFolder.resolve(UUID.randomUUID().toString()), "data".getBytes());
    var directoryNamedLikeAnUpload = Files.createDirectory(segmentFolder.resolve(".upload-1.tmp"));
    var fileWithOnlyAnUploadsPrefix =
        Files.write(segmentFolder.resolve(".upload-3"), "data".getBytes());
    var outsideFile = Files.write(outside.resolve("movie.mkv"), "media".getBytes());
    var linkNamedLikeASession =
        Files.createSymbolicLink(segmentFolder.resolve(UUID.randomUUID().toString()), outside);
    var linkNamedLikeAnUpload =
        Files.createSymbolicLink(segmentFolder.resolve(".upload-2.tmp"), outsideFile);

    leftoversOf(segmentFolder).delete();

    assertThat(otherFile).exists();
    assertThat(otherDirectory).exists();
    assertThat(fileNamedLikeASession).exists();
    assertThat(directoryNamedLikeAnUpload).exists();
    assertThat(fileWithOnlyAnUploadsPrefix).exists();
    assertThat(linkNamedLikeASession).isSymbolicLink();
    assertThat(linkNamedLikeAnUpload).isSymbolicLink();
    assertThat(outsideFile).exists();
  }

  @Test
  @DisplayName(
      "Should keep a folder outside the segment folder when a leftover stream session's directory"
          + " links to it")
  void shouldKeepAFolderOutsideTheSegmentFolderWhenALeftoverStreamSessionsDirectoryLinksToIt()
      throws IOException {
    leftoversOf(segmentFolder).delete();
    var sessionId = UUID.randomUUID();
    var sessionDirectory = earlierStore().getOutputDirectory(sessionId);
    var outsideFile = Files.write(outside.resolve("movie.mkv"), "media".getBytes());
    Files.createSymbolicLink(sessionDirectory.resolve("link"), outside);

    leftoversOf(segmentFolder).delete();

    assertThat(outsideFile).exists();
    assertThat(sessionDirectory).doesNotExist();
  }

  @Test
  @DisplayName("Should delete the other leftovers when it cannot delete one")
  void shouldDeleteTheOtherLeftoversWhenItCannotDeleteOne() throws IOException {
    leftoversOf(segmentFolder).delete();
    var store = earlierStore();
    leftover(store);
    leftover(store);
    leftover(store);
    // The leftover that a listing returns first, so a delete that stopped at its first failure
    // would keep the two after it.
    var undeletable = firstListedDirectory();
    var lockedVariantDirectory = lock(undeletable.resolve("720p"));
    try {
      assumeFalse(
          Files.isWritable(lockedVariantDirectory), "folder permissions apply to the test user");

      assertThatNoException().isThrownBy(leftoversOf(segmentFolder)::delete);

      assertThat(lockedVariantDirectory.resolve("0.m4s")).exists();
      assertThat(segmentFolder)
          .isDirectoryNotContaining(
              entry -> Files.isDirectory(entry) && !entry.equals(undeletable));
    } finally {
      unlock(lockedVariantDirectory);
    }
  }

  // Each delete of a directory opens the segment folder again. On macOS a listing that is open
  // meanwhile then skips some of a few hundred entries.
  @Test
  @DisplayName("Should delete every leftover when the segment folder holds hundreds of them")
  void shouldDeleteEveryLeftoverWhenTheSegmentFolderHoldsHundredsOfThem() throws IOException {
    new SegmentFolderLeftovers(segmentFolder).delete();
    var store = earlierStore();
    for (var i = 0; i < 300; i++) {
      leftover(store);
    }

    new SegmentFolderLeftovers(segmentFolder).delete();

    assertThat(segmentFolder).isDirectoryNotContaining(Files::isDirectory);
  }

  @Test
  @DisplayName("Should not throw when the segment folder cannot be created")
  void shouldNotThrowWhenTheSegmentFolderCannotBeCreated() throws IOException {
    var fileInTheFoldersPlace = Files.write(segmentFolder.resolve("segments"), "data".getBytes());

    assertThatNoException().isThrownBy(leftoversOf(fileInTheFoldersPlace)::delete);

    assertThat(fileInTheFoldersPlace).isRegularFile().hasBinaryContent("data".getBytes());
  }

  @Test
  @DisplayName("Should reject a missing segment folder when created")
  void shouldRejectAMissingSegmentFolderWhenCreated() {
    assertThatNullPointerException().isThrownBy(SegmentFolderLeftovers.builder()::build);
  }

  private static SegmentFolderLeftovers leftoversOf(Path folder) {
    return SegmentFolderLeftovers.builder().segmentFolder(folder).build();
  }

  private LocalSegmentStore earlierStore() {
    return new LocalSegmentStore(segmentFolder);
  }

  private static void leftover(LocalSegmentStore store) throws IOException {
    Files.write(store.getOutputDirectory(UUID.randomUUID(), "720p").resolve("0.m4s"), SEGMENT);
  }

  private Path firstListedDirectory() throws IOException {
    try (var directories = Files.newDirectoryStream(segmentFolder, Files::isDirectory)) {
      return directories.iterator().next();
    }
  }

  // A variant directory that cannot be written keeps its segment, so the session's directory
  // cannot be deleted.
  private static Path lock(Path variantDirectory) throws IOException {
    return Files.setPosixFilePermissions(
        variantDirectory, PosixFilePermissions.fromString("r-x------"));
  }

  private static void unlock(Path variantDirectory) throws IOException {
    Files.setPosixFilePermissions(variantDirectory, PosixFilePermissions.fromString("rwx------"));
  }
}
