package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Deleted;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Refused;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Segment Folder Leftovers Tests")
class SegmentFolderLeftoversTest {

  private static final byte[] SEGMENT = "segment".getBytes();
  private static final byte[] MEDIA = "media".getBytes();
  private static final String MARK_NAME = ".streamarr-segment-folder";
  private static final String SESSION_MARK_NAME = ".streamarr-stream-session";
  private static final String LOCK_NAME = ".streamarr-segment-folder.lock";

  @TempDir Path segmentFolder;
  @TempDir Path outside;

  @Test
  @DisplayName("Should delete a stream session's directory when an earlier store left it")
  void shouldDeleteAStreamSessionsDirectoryWhenAnEarlierStoreLeftIt() {
    startOf(segmentFolder);
    var sessionId = UUID.randomUUID();
    try (var stored = earlierStore().prepareSegment(sessionId, "720p/0.m4s", SEGMENT)) {
      stored.publish();
    }

    var outcome = startOf(segmentFolder);

    assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
    assertThat(outcome).isEqualTo(new Deleted(1, 0));
  }

  @Test
  @DisplayName("Should delete an unpublished upload when an earlier store left it")
  void shouldDeleteAnUnpublishedUploadWhenAnEarlierStoreLeftIt() {
    startOf(segmentFolder);
    // Still open: the store's process ended before it published or discarded the upload.
    try (var _ = earlierStore().prepareSegment(UUID.randomUUID(), "720p/0.m4s", SEGMENT)) {
      assertThat(segmentFolder).isDirectoryContaining("glob:**/.upload-*");

      var outcome = startOf(segmentFolder);

      assertThat(segmentFolder).isDirectoryNotContaining("glob:**/.upload-*");
      assertThat(outcome).isEqualTo(new Deleted(1, 0));
    }
  }

  @Test
  @DisplayName("Should keep the segment folder itself when it deletes every leftover")
  void shouldKeepTheSegmentFolderItselfWhenItDeletesEveryLeftover() {
    startOf(segmentFolder);
    earlierStore().getOutputDirectory(UUID.randomUUID());

    startOf(segmentFolder);

    assertThat(segmentFolder).isDirectory();
    assertThat(segmentFolder.resolve(MARK_NAME)).isRegularFile();
  }

  @Test
  @DisplayName("Should delete leftovers when it created the segment folder")
  void shouldDeleteLeftoversWhenItCreatedTheSegmentFolder() {
    var created = segmentFolder.resolve("server").resolve("segments");
    startOf(created);
    var sessionId = UUID.randomUUID();
    new LocalSegmentStore(created, new SegmentFolderLock()).getOutputDirectory(sessionId);

    startOf(created);

    assertThat(created.resolve(sessionId.toString())).doesNotExist();
  }

  @Test
  @DisplayName("Should keep everything when the folder held other entries before a store used it")
  void shouldKeepEverythingWhenTheFolderHeldOtherEntriesBeforeAStoreUsedIt() throws IOException {
    var fileInThatDirectory = deletableFileIn(segmentFolder);
    var directoryNamedLikeASession = fileInThatDirectory.getParent();
    var fileNamedLikeAnUpload = Files.write(segmentFolder.resolve(".upload-1.tmp"), MEDIA);

    startOf(segmentFolder);
    var outcome = startOf(segmentFolder);

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.UNMARKED_FOLDER));
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
    startOf(segmentFolder);
    var sessionId = UUID.randomUUID();
    earlierStore().getOutputDirectory(sessionId);

    startOf(segmentFolder);

    assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
  }

  // Bytes that are no UTF-8 text cannot be read as a folder's path.
  @Test
  @DisplayName("Should delete leftovers when the folder held only a mark that is not text")
  void shouldDeleteLeftoversWhenTheFolderHeldOnlyAMarkThatIsNotText() throws IOException {
    Files.write(segmentFolder.resolve(MARK_NAME), new byte[] {(byte) 0xC3, 0x28});
    startOf(segmentFolder);
    var sessionId = UUID.randomUUID();
    earlierStore().getOutputDirectory(sessionId);

    startOf(segmentFolder);

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
    var outsideFile = Files.write(outside.resolve("movie.mkv"), MEDIA);
    Files.createSymbolicLink(segmentFolder.resolve(MARK_NAME), outsideFile);
    startOf(segmentFolder);
    var sessionId = UUID.randomUUID();
    earlierStore().getOutputDirectory(sessionId);

    startOf(segmentFolder);

    assertThat(outsideFile).hasBinaryContent(MEDIA);
    assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
  }

  // Copying a segment folder's entries into another folder brings the mark along.
  @Test
  @DisplayName(
      "Should keep everything when the folder's mark was copied from another segment folder")
  void shouldKeepEverythingWhenTheFoldersMarkWasCopiedFromAnotherSegmentFolder()
      throws IOException {
    var otherSegmentFolder = outside.resolve("segments");
    startOf(otherSegmentFolder);
    var fileInThatDirectory = deletableFileIn(segmentFolder);
    Files.copy(otherSegmentFolder.resolve(MARK_NAME), segmentFolder.resolve(MARK_NAME));

    startOf(segmentFolder);

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
    startOf(segmentFolder);
    var markOutsideTheFolder =
        Files.move(segmentFolder.resolve(MARK_NAME), outside.resolve(MARK_NAME));
    Files.createSymbolicLink(segmentFolder.resolve(MARK_NAME), markOutsideTheFolder);
    var fileInThatDirectory = deletableFileIn(segmentFolder);

    startOf(segmentFolder);

    assertThat(fileInThatDirectory).exists();
  }

  @Test
  @DisplayName("Should keep each entry that no store writes when it deletes leftovers")
  void shouldKeepEachEntryThatNoStoreWritesWhenItDeletesLeftovers() throws IOException {
    startOf(segmentFolder);
    var otherFile = Files.write(segmentFolder.resolve("notes.txt"), "notes".getBytes());
    var otherDirectory = Files.createDirectory(segmentFolder.resolve("movies"));
    var fileNamedLikeASession =
        Files.write(segmentFolder.resolve(UUID.randomUUID().toString()), "data".getBytes());
    var directoryNamedLikeAnUpload = Files.createDirectory(segmentFolder.resolve(".upload-1.tmp"));
    var fileWithOnlyAnUploadsPrefix =
        Files.write(segmentFolder.resolve(".upload-3"), "data".getBytes());
    var outsideFile = Files.write(outside.resolve("movie.mkv"), MEDIA);
    var linkNamedLikeASession =
        Files.createSymbolicLink(segmentFolder.resolve(UUID.randomUUID().toString()), outside);
    var linkNamedLikeAnUpload =
        Files.createSymbolicLink(segmentFolder.resolve(".upload-2.tmp"), outsideFile);

    var outcome = startOf(segmentFolder);

    assertThat(otherFile).exists();
    assertThat(otherDirectory).exists();
    assertThat(fileNamedLikeASession).exists();
    assertThat(directoryNamedLikeAnUpload).exists();
    assertThat(fileWithOnlyAnUploadsPrefix).exists();
    assertThat(linkNamedLikeASession).isSymbolicLink();
    assertThat(linkNamedLikeAnUpload).isSymbolicLink();
    assertThat(outsideFile).exists();
    assertThat(outcome).isEqualTo(new Deleted(0, 0));
  }

  // Only a store's mark in the directory shows that a store created it: anything can take the name.
  @Test
  @DisplayName(
      "Should keep a directory named like a stream session's when no store marked it as one")
  void shouldKeepADirectoryNamedLikeAStreamSessionsWhenNoStoreMarkedItAsOne() throws IOException {
    startOf(segmentFolder);
    var directory = Files.createDirectory(segmentFolder.resolve(UUID.randomUUID().toString()));
    var file = Files.write(directory.resolve("movie.mkv"), MEDIA);

    var outcome = startOf(segmentFolder);

    assertThat(file).hasBinaryContent(MEDIA);
    assertThat(outcome).isEqualTo(new Deleted(0, 0));
  }

  @Test
  @DisplayName(
      "Should keep a directory named like a stream session's when a soft link holds its mark's"
          + " place")
  void shouldKeepADirectoryNamedLikeAStreamSessionsWhenASoftLinkHoldsItsMarksPlace()
      throws IOException {
    startOf(segmentFolder);
    var directory = Files.createDirectory(segmentFolder.resolve(UUID.randomUUID().toString()));
    var file = Files.write(directory.resolve("movie.mkv"), MEDIA);
    var outsideMark = Files.createFile(outside.resolve(SESSION_MARK_NAME));
    Files.createSymbolicLink(directory.resolve(SESSION_MARK_NAME), outsideMark);

    var outcome = startOf(segmentFolder);

    assertThat(file).hasBinaryContent(MEDIA);
    assertThat(outcome).isEqualTo(new Deleted(0, 0));
  }

  @Test
  @DisplayName(
      "Should keep a running server process's stream sessions when another process starts on its"
          + " folder")
  void shouldKeepARunningServerProcesssStreamSessionsWhenAnotherProcessStartsOnItsFolder()
      throws IOException {
    try (var runningProcessLock = new SegmentFolderLock()) {
      leftoversOf(segmentFolder).lock(runningProcessLock).build().delete();
      var runningSession = earlierStore().getOutputDirectory(UUID.randomUUID());

      var outcome = startOf(segmentFolder);

      assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.IN_USE));
      assertThat(runningSession).isDirectory();
    }
  }

  @Test
  @DisplayName("Should delete a stream session's directory when the process that left it ended")
  void shouldDeleteAStreamSessionsDirectoryWhenTheProcessThatLeftItEnded() throws IOException {
    Path leftover;
    try (var endedProcessLock = new SegmentFolderLock()) {
      leftoversOf(segmentFolder).lock(endedProcessLock).build().delete();
      leftover = earlierStore().getOutputDirectory(UUID.randomUUID());
    }

    var outcome = startOf(segmentFolder);

    assertThat(outcome).isEqualTo(new Deleted(1, 0));
    assertThat(leftover).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should keep the file that a soft link in the lock's place points to and delete nothing")
  void shouldKeepTheFileThatASoftLinkInTheLocksPlacePointsToAndDeleteNothing() throws IOException {
    startOf(segmentFolder);
    var leftover = earlierStore().getOutputDirectory(UUID.randomUUID());
    var outsideFile = Files.write(outside.resolve("movie.mkv"), MEDIA);
    Files.delete(segmentFolder.resolve(LOCK_NAME));
    Files.createSymbolicLink(segmentFolder.resolve(LOCK_NAME), outsideFile);

    var outcome = startOf(segmentFolder);

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.UNREADABLE_FOLDER));
    assertThat(outsideFile).hasBinaryContent(MEDIA);
    assertThat(leftover).isDirectory();
  }

  @Test
  @DisplayName(
      "Should keep a folder outside the segment folder when a leftover stream session's directory"
          + " links to it")
  void shouldKeepAFolderOutsideTheSegmentFolderWhenALeftoverStreamSessionsDirectoryLinksToIt()
      throws IOException {
    startOf(segmentFolder);
    var sessionId = UUID.randomUUID();
    var sessionDirectory = earlierStore().getOutputDirectory(sessionId);
    var outsideFile = Files.write(outside.resolve("movie.mkv"), MEDIA);
    Files.createSymbolicLink(sessionDirectory.resolve("link"), outside);

    startOf(segmentFolder);

    assertThat(outsideFile).hasBinaryContent(MEDIA);
    assertThat(sessionDirectory).doesNotExist();
  }

  @Test
  @DisplayName("Should delete the other leftovers and count the rest when it cannot delete some")
  void shouldDeleteTheOtherLeftoversAndCountTheRestWhenItCannotDeleteSome() throws IOException {
    startOf(segmentFolder);
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

      var outcome = startOf(segmentFolder);

      assertThat(lockedVariantDirectory.resolve("0.m4s")).exists();
      assertThat(segmentFolder)
          .isDirectoryNotContaining(
              entry -> Files.isDirectory(entry) && !entry.equals(undeletable));
      assertThat(outcome).isEqualTo(new Deleted(2, 1));
    } finally {
      unlock(lockedVariantDirectory);
    }
  }

  // Each delete of a directory opens the segment folder again. On macOS a listing that is open
  // meanwhile then skips some of a few hundred entries.
  @Test
  @DisplayName("Should delete every leftover when the segment folder holds hundreds of them")
  void shouldDeleteEveryLeftoverWhenTheSegmentFolderHoldsHundredsOfThem() throws IOException {
    startOf(segmentFolder);
    var store = earlierStore();
    for (var i = 0; i < 300; i++) {
      leftover(store);
    }

    var outcome = startOf(segmentFolder);

    assertThat(segmentFolder).isDirectoryNotContaining(Files::isDirectory);
    assertThat(outcome).isEqualTo(new Deleted(300, 0));
  }

  @Test
  @DisplayName(
      "Should refuse the folder without throwing when the segment folder cannot be created")
  void shouldRefuseTheFolderWithoutThrowingWhenTheSegmentFolderCannotBeCreated()
      throws IOException {
    var fileInTheFoldersPlace = Files.write(segmentFolder.resolve("segments"), "data".getBytes());

    var outcome = startOf(fileInTheFoldersPlace);

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.UNREADABLE_FOLDER));
    assertThat(fileInTheFoldersPlace).isRegularFile().hasBinaryContent("data".getBytes());
  }

  @Test
  @DisplayName("Should refuse the folder when it is a media folder")
  void shouldRefuseTheFolderWhenItIsAMediaFolder() throws IOException {
    startOf(segmentFolder);
    var movie = deletableFileIn(segmentFolder);

    var outcome = startOf(leftoversOf(segmentFolder).mediaFolders(() -> List.of(segmentFolder)));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.MEDIA_FOLDER));
    assertThat(movie).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName("Should refuse the folder when it holds a media folder")
  void shouldRefuseTheFolderWhenItHoldsAMediaFolder() throws IOException {
    startOf(segmentFolder);
    var movie = deletableFileIn(segmentFolder);
    var library = segmentFolder.resolve("movies");

    var outcome = startOf(leftoversOf(segmentFolder).mediaFolders(() -> List.of(library)));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.MEDIA_FOLDER));
    assertThat(movie).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName("Should refuse the folder and create nothing when it lies in a media folder")
  void shouldRefuseTheFolderAndCreateNothingWhenItLiesInAMediaFolder() {
    var segmentsInTheLibrary = outside.resolve("segments");

    var outcome = startOf(leftoversOf(segmentsInTheLibrary).mediaFolders(() -> List.of(outside)));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.MEDIA_FOLDER));
    assertThat(segmentsInTheLibrary).doesNotExist();
  }

  @Test
  @DisplayName("Should refuse the folder when a soft link names it and leads into a media folder")
  void shouldRefuseTheFolderWhenASoftLinkNamesItAndLeadsIntoAMediaFolder() throws IOException {
    var library = Files.createDirectory(outside.resolve("movies"));
    startOf(library);
    var movie = deletableFileIn(library);
    var link = Files.createSymbolicLink(segmentFolder.resolve("segments"), library);

    var outcome = startOf(leftoversOf(link).mediaFolders(() -> List.of(library)));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.MEDIA_FOLDER));
    assertThat(movie).hasBinaryContent(MEDIA);
  }

  // Path.startsWith compares whole names: /library/segments2 does not lie in /library/segments.
  @Test
  @DisplayName("Should delete leftovers when a media folder's name only starts like the folder's")
  void shouldDeleteLeftoversWhenAMediaFoldersNameOnlyStartsLikeTheFolders() {
    var segments = outside.resolve("segments");
    var library = outside.resolve("segments2");
    startOf(segments);
    var sessionId = UUID.randomUUID();
    new LocalSegmentStore(segments, new SegmentFolderLock()).getOutputDirectory(sessionId);

    var outcome = startOf(leftoversOf(segments).mediaFolders(() -> List.of(library)));

    assertThat(outcome).isEqualTo(new Deleted(1, 0));
    assertThat(segments.resolve(sessionId.toString())).doesNotExist();
  }

  @Test
  @DisplayName("Should refuse every folder when the media folders cannot be read")
  void shouldRefuseEveryFolderWhenTheMediaFoldersCannotBeRead() {
    startOf(segmentFolder);
    var sessionId = UUID.randomUUID();
    earlierStore().getOutputDirectory(sessionId);
    Supplier<List<Path>> unreadable =
        () -> {
          throw new IllegalStateException("The library table cannot be read");
        };

    var outcome = startOf(leftoversOf(segmentFolder).mediaFolders(unreadable));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.UNKNOWN_MEDIA_FOLDERS));
    assertThat(segmentFolder.resolve(sessionId.toString())).isDirectory();
  }

  @Test
  @DisplayName("Should refuse the folder when it is the home folder")
  void shouldRefuseTheFolderWhenItIsTheHomeFolder() throws IOException {
    startOf(segmentFolder);
    var file = deletableFileIn(segmentFolder);

    var outcome = startOf(leftoversOf(segmentFolder).homeFolder(segmentFolder));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.HOME_FOLDER));
    assertThat(file).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName("Should refuse the folder when it holds the home folder")
  void shouldRefuseTheFolderWhenItHoldsTheHomeFolder() throws IOException {
    startOf(segmentFolder);
    var file = deletableFileIn(segmentFolder);
    var home = segmentFolder.resolve("home").resolve("streamarr");

    var outcome = startOf(leftoversOf(segmentFolder).homeFolder(home));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.HOME_FOLDER));
    assertThat(file).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName("Should refuse the folder and create nothing when it is a file system's top folder")
  void shouldRefuseTheFolderAndCreateNothingWhenItIsAFileSystemsTopFolder() throws IOException {
    try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
      var top = fileSystem.getPath("/");

      var outcome =
          startOf(
              SegmentFolderLeftovers.builder()
                  .segmentFolder(top)
                  .homeFolder(fileSystem.getPath("/home/streamarr"))
                  .mediaFolders(() -> List.of(fileSystem.getPath("/media"))));

      assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.FILE_SYSTEM_ROOT));
      assertThat(top.resolve(MARK_NAME)).doesNotExist();
    }
  }

  @Test
  @DisplayName("Should reject a missing segment folder when created")
  void shouldRejectAMissingSegmentFolderWhenCreated() {
    assertThatNullPointerException()
        .isThrownBy(() -> leftoversOf(segmentFolder).segmentFolder(null).build());
  }

  @Test
  @DisplayName("Should reject a missing home folder when created")
  void shouldRejectAMissingHomeFolderWhenCreated() {
    assertThatNullPointerException()
        .isThrownBy(() -> leftoversOf(segmentFolder).homeFolder(null).build());
  }

  @Test
  @DisplayName("Should reject a missing lock when created")
  void shouldRejectAMissingLockWhenCreated() {
    assertThatNullPointerException()
        .isThrownBy(() -> leftoversOf(segmentFolder).lock(null).build());
  }

  @Test
  @DisplayName("Should reject missing media folders when created")
  void shouldRejectMissingMediaFoldersWhenCreated() {
    assertThatNullPointerException()
        .isThrownBy(() -> leftoversOf(segmentFolder).mediaFolders(null).build());
  }

  // The start of a server process whose home folder and media folders lie elsewhere.
  private SegmentFolderLeftovers.SegmentFolderLeftoversBuilder leftoversOf(Path folder) {
    return SegmentFolderLeftovers.builder()
        .segmentFolder(folder)
        .homeFolder(outside.resolve("home"))
        .mediaFolders(() -> List.of(outside.resolve("media")))
        .lock(new SegmentFolderLock());
  }

  private SegmentFolderLeftoversOutcome startOf(Path folder) {
    return startOf(leftoversOf(folder));
  }

  // A server process that starts and then ends, which releases its lock of the folder.
  private static SegmentFolderLeftoversOutcome startOf(
      SegmentFolderLeftovers.SegmentFolderLeftoversBuilder start) {
    try (var lock = new SegmentFolderLock()) {
      return start.lock(lock).build().delete();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private LocalSegmentStore earlierStore() {
    return new LocalSegmentStore(segmentFolder, new SegmentFolderLock());
  }

  // A file in a stream session's directory that a store wrote, which a start that deletes would
  // remove with the directory.
  private static Path deletableFileIn(Path folder) throws IOException {
    var directory =
        new LocalSegmentStore(folder, new SegmentFolderLock())
            .getOutputDirectory(UUID.randomUUID());
    return Files.write(directory.resolve("movie.mkv"), MEDIA);
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
