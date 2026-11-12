package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.fakes.ThrowingFileSystemWrapper;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Deleted;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Refused;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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

  // The mount table of a process that sees only the root file system.
  private static final String ROOT_FILE_SYSTEM =
      "22 1 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw\n";

  @TempDir Path segmentFolder;
  @TempDir Path outside;
  @TempDir Path machine;

  @Test
  @DisplayName("Should delete a stream session's directory when an earlier store left it")
  void shouldDeleteAStreamSessionsDirectoryWhenAnEarlierStoreLeftIt() {
    startOf(segmentFolder);
    var sessionId = UUID.randomUUID();
    leftByAnEndedProcess(
        store -> {
          try (var stored = store.prepareSegment(sessionId, "720p/0.m4s", SEGMENT)) {
            return stored.publish();
          }
        });

    var outcome = startOf(segmentFolder);

    assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
    assertThat(outcome).isEqualTo(new Deleted(1, 0));
  }

  @Test
  @DisplayName("Should delete an unpublished upload when an earlier store left it")
  void shouldDeleteAnUnpublishedUploadWhenAnEarlierStoreLeftIt() {
    startOf(segmentFolder);
    // Still open: the store's process ended before it published or discarded the upload.
    try (var _ =
        leftByAnEndedProcess(
            store -> store.prepareSegment(UUID.randomUUID(), "720p/0.m4s", SEGMENT))) {
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
    leftSessionIn(segmentFolder);

    startOf(segmentFolder);

    assertThat(segmentFolder).isDirectory();
    assertThat(segmentFolder.resolve(MARK_NAME)).isRegularFile();
  }

  @Test
  @DisplayName("Should delete leftovers when it created the segment folder")
  void shouldDeleteLeftoversWhenItCreatedTheSegmentFolder() {
    var created = segmentFolder.resolve("server").resolve("segments");
    startOf(created);
    var session = leftSessionIn(created);

    startOf(created);

    assertThat(session).doesNotExist();
  }

  @Test
  @DisplayName("Should keep everything when the folder held other entries before a store used it")
  void shouldKeepEverythingWhenTheFolderHeldOtherEntriesBeforeAStoreUsedIt() throws IOException {
    var fileInThatDirectory = deletableFileIn(segmentFolder);
    var fileNamedLikeAnUpload = Files.write(segmentFolder.resolve(".upload-1.tmp"), MEDIA);
    var entriesBefore = entriesOf(segmentFolder);

    startOf(segmentFolder);
    var outcome = startOf(segmentFolder);

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.UNMARKED_FOLDER));
    assertThat(fileInThatDirectory).exists();
    assertThat(fileNamedLikeAnUpload).exists();
    assertThat(entriesOf(segmentFolder)).isEqualTo(entriesBefore);
  }

  // A process that ends between creating the mark and writing it leaves an empty mark.
  @Test
  @DisplayName(
      "Should delete leftovers when an earlier process ended before it wrote the folder's mark")
  void shouldDeleteLeftoversWhenAnEarlierProcessEndedBeforeItWroteTheFoldersMark()
      throws IOException {
    Files.createFile(segmentFolder.resolve(MARK_NAME));
    startOf(segmentFolder);
    var session = leftSessionIn(segmentFolder);

    startOf(segmentFolder);

    assertThat(session).doesNotExist();
  }

  // Bytes that are no UTF-8 text cannot be read as a folder's path.
  @Test
  @DisplayName("Should delete leftovers when the folder held only a mark that is not text")
  void shouldDeleteLeftoversWhenTheFolderHeldOnlyAMarkThatIsNotText() throws IOException {
    Files.write(segmentFolder.resolve(MARK_NAME), new byte[] {(byte) 0xC3, 0x28});
    startOf(segmentFolder);
    var session = leftSessionIn(segmentFolder);

    startOf(segmentFolder);

    assertThat(session).doesNotExist();
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

    startOf(segmentFolder);
    var session = leftSessionIn(segmentFolder);

    startOf(segmentFolder);

    assertThat(session).doesNotExist();
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
    var session = leftSessionIn(segmentFolder);

    startOf(segmentFolder);

    assertThat(outsideFile).hasBinaryContent(MEDIA);
    assertThat(session).doesNotExist();
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
    startOf(markedFolder);
    var fileInThatDirectory = deletableFileIn(folderWithTheCopiedMark);
    Files.copy(markedFolder.resolve(MARK_NAME), folderWithTheCopiedMark.resolve(MARK_NAME));

    var outcome = startOf(folderWithTheCopiedMark);

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.UNMARKED_FOLDER));
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
      var runningSession =
          new LocalSegmentStore(segmentFolder, runningProcessLock)
              .getOutputDirectory(UUID.randomUUID());

      var outcome = startOf(segmentFolder);

      assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.IN_USE));
      assertThat(runningSession).isDirectory();
    }
  }

  @Test
  @DisplayName(
      "Should keep a running server process's stream sessions when its own start refused the"
          + " folder")
  void shouldKeepARunningServerProcesssStreamSessionsWhenItsOwnStartRefusedTheFolder()
      throws IOException {
    startOf(segmentFolder);
    try (var runningProcessLock = new SegmentFolderLock()) {
      var refusedStart =
          leftoversOf(segmentFolder)
              .mediaFolders(unreadableMediaFolders())
              .lock(runningProcessLock)
              .build()
              .delete();
      var runningSession =
          new LocalSegmentStore(segmentFolder, runningProcessLock)
              .getOutputDirectory(UUID.randomUUID());

      var outcome = startOf(segmentFolder);

      assertThat(refusedStart).isEqualTo(new Refused(SegmentFolderRefusal.UNKNOWN_MEDIA_FOLDERS));
      assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.IN_USE));
      assertThat(runningSession).isDirectory();
    }
  }

  // A running process can have no start of its own on the folder, as after a refusal: only what
  // its store writes shows that it uses the folder.
  @Test
  @DisplayName(
      "Should keep what a running server process's store wrote when that process ran no start on"
          + " the folder")
  void shouldKeepWhatARunningServerProcesssStoreWroteWhenThatProcessRanNoStartOnTheFolder()
      throws IOException {
    startOf(segmentFolder);
    try (var runningProcessLock = new SegmentFolderLock()) {
      var runningStore = new LocalSegmentStore(segmentFolder, runningProcessLock);
      var runningSession = runningStore.getOutputDirectory(UUID.randomUUID());
      try (var _ = runningStore.prepareSegment(UUID.randomUUID(), "720p/0.m4s", SEGMENT)) {

        var outcome = startOf(segmentFolder);

        assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.IN_USE));
        assertThat(runningSession).isDirectory();
        assertThat(segmentFolder).isDirectoryContaining("glob:**/.upload-*");
      }
    }
  }

  @Test
  @DisplayName(
      "Should keep the stream sessions of each running server process when two processes write into"
          + " the folder")
  void shouldKeepTheStreamSessionsOfEachRunningServerProcessWhenTwoProcessesWriteIntoTheFolder()
      throws IOException {
    startOf(segmentFolder);
    try (var firstProcessLock = new SegmentFolderLock();
        var secondProcessLock = new SegmentFolderLock()) {
      var firstSession =
          new LocalSegmentStore(segmentFolder, firstProcessLock)
              .getOutputDirectory(UUID.randomUUID());
      var secondSession =
          new LocalSegmentStore(segmentFolder, secondProcessLock)
              .getOutputDirectory(UUID.randomUUID());

      var outcome = startOf(segmentFolder);

      assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.IN_USE));
      assertThat(firstSession).isDirectory();
      assertThat(secondSession).isDirectory();
    }
  }

  @Test
  @DisplayName(
      "Should leave the folder's mark to the start that holds the folder's lock when two server"
          + " processes start at once")
  void shouldLeaveTheFoldersMarkToTheStartThatHoldsTheFoldersLockWhenTwoServerProcessesStartAtOnce()
      throws IOException {
    try (var otherStartsLock = new SegmentFolderLock()) {
      var outcome = otherStartsLock.whileAlone(segmentFolder, () -> startOf(segmentFolder));

      assertThat(outcome).contains(new Refused(SegmentFolderRefusal.IN_USE));
      assertThat(segmentFolder.resolve(MARK_NAME)).doesNotExist();
    }
  }

  @Test
  @DisplayName("Should delete a stream session's directory when the process that left it ended")
  void shouldDeleteAStreamSessionsDirectoryWhenTheProcessThatLeftItEnded() throws IOException {
    Path leftover;
    try (var endedProcessLock = new SegmentFolderLock()) {
      leftoversOf(segmentFolder).lock(endedProcessLock).build().delete();
      leftover =
          new LocalSegmentStore(segmentFolder, endedProcessLock)
              .getOutputDirectory(UUID.randomUUID());
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
    var leftover = leftSessionIn(segmentFolder);
    var outsideFile = Files.write(outside.resolve("movie.mkv"), MEDIA);
    Files.delete(segmentFolder.resolve(LOCK_NAME));
    Files.createSymbolicLink(segmentFolder.resolve(LOCK_NAME), outsideFile);

    var outcome = startOf(segmentFolder);

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.UNREADABLE_FOLDER));
    assertThat(outsideFile).hasBinaryContent(MEDIA);
    assertThat(leftover).isDirectory();
  }

  // A bind mount of the folder's own file system crosses no device boundary, so only the mount
  // table shows it.
  @Test
  @DisplayName(
      "Should refuse the folder and keep what is mounted there when a file system is mounted in a"
          + " leftover")
  void shouldRefuseTheFolderAndKeepWhatIsMountedThereWhenAFileSystemIsMountedInALeftover()
      throws IOException {
    startOf(segmentFolder);
    var session = leftSessionIn(segmentFolder);
    var mountPoint = Files.createDirectory(session.resolve("library"));
    var movie = Files.write(mountPoint.resolve("movie.mkv"), MEDIA);

    var outcome =
        startOf(
            leftoversOf(segmentFolder)
                .mountTable(
                    mountTableOf(
                        """
                        22 1 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw
                        41 22 8:1 /srv/media %s rw,relatime shared:1 - ext4 /dev/sda1 rw
                        """,
                        mountPoint)));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.MOUNT_POINT_INSIDE));
    assertThat(movie).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName("Should refuse the folder when the mount table cannot be read")
  void shouldRefuseTheFolderWhenTheMountTableCannotBeRead() {
    startOf(segmentFolder);
    var session = leftSessionIn(segmentFolder);

    var outcome =
        startOf(leftoversOf(segmentFolder).mountTable(machine.resolve("missing-mountinfo")));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.UNKNOWN_MOUNT_POINTS));
    assertThat(session).isDirectory();
  }

  // A volume of its own for segments, such as a tmpfs, is mounted on the folder itself.
  @Test
  @DisplayName("Should delete leftovers when a file system is mounted on the folder itself")
  void shouldDeleteLeftoversWhenAFileSystemIsMountedOnTheFolderItself() {
    startOf(segmentFolder);
    var session = leftSessionIn(segmentFolder);

    var outcome =
        startOf(
            leftoversOf(segmentFolder)
                .mountTable(
                    mountTableOf(
                        """
                        22 1 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw
                        41 22 0:41 / %s rw,nosuid,nodev shared:2 - tmpfs tmpfs rw
                        """,
                        segmentFolder)));

    assertThat(outcome).isEqualTo(new Deleted(1, 0));
    assertThat(session).doesNotExist();
  }

  // Files.createTempFile puts only digits between an upload's prefix and suffix.
  @Test
  @DisplayName("Should keep a file that is named like an upload but not as a store names one")
  void shouldKeepAFileThatIsNamedLikeAnUploadButNotAsAStoreNamesOne() throws IOException {
    startOf(segmentFolder);
    var file = Files.write(segmentFolder.resolve(".upload-family-photos.tmp"), MEDIA);

    var outcome = startOf(segmentFolder);

    assertThat(outcome).isEqualTo(new Deleted(0, 0));
    assertThat(file).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName(
      "Should keep a folder outside the segment folder when a leftover stream session's directory"
          + " links to it")
  void shouldKeepAFolderOutsideTheSegmentFolderWhenALeftoverStreamSessionsDirectoryLinksToIt()
      throws IOException {
    startOf(segmentFolder);
    var sessionDirectory = leftSessionIn(segmentFolder);
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
    leftover(segmentFolder);
    leftover(segmentFolder);
    leftover(segmentFolder);
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

  @Test
  @DisplayName(
      "Should delete the rest of a leftover at a later start when an earlier start could not delete"
          + " all of it")
  void shouldDeleteTheRestOfALeftoverAtALaterStartWhenAnEarlierStartCouldNotDeleteAllOfIt()
      throws IOException {
    startOf(segmentFolder);
    var leftover = leftover(segmentFolder);
    var lockedVariantDirectory = lock(leftover.resolve("720p"));
    try {
      assumeFalse(
          Files.isWritable(lockedVariantDirectory), "folder permissions apply to the test user");
      startOf(segmentFolder);
    } finally {
      unlock(lockedVariantDirectory);
    }

    var outcome = startOf(segmentFolder);

    assertThat(leftover).doesNotExist();
    assertThat(outcome).isEqualTo(new Deleted(1, 0));
  }

  // A folder elsewhere that holds a directory with a leftover's name was never the server's.
  @Test
  @DisplayName(
      "Should keep another folder's files when the segment folder's soft link is pointed there"
          + " while the start deletes")
  void
      shouldKeepAnotherFoldersFilesWhenTheSegmentFoldersSoftLinkIsPointedThereWhileTheStartDeletes()
          throws IOException {
    try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
      tableAt(
          Files.createDirectories(fileSystem.getPath("/proc/self")).resolve("mountinfo"),
          ROOT_FILE_SYSTEM);
      var markedFolder = Files.createDirectories(fileSystem.getPath("/var/lib/streamarr/segments"));
      var link =
          Files.createSymbolicLink(
              Files.createDirectories(fileSystem.getPath("/srv")).resolve("segments"),
              markedFolder);
      startOf(leftoversInMemory(link));
      var sessionName = leftSessionIn(link).getFileName().toString();
      var otherFolder = Files.createDirectories(fileSystem.getPath("/backup"));
      var movie =
          Files.write(
              Files.createDirectory(otherFolder.resolve(sessionName)).resolve("movie.mkv"), MEDIA);
      var pointedElsewhereOnceItReadsASessionsMark =
          ThrowingFileSystemWrapper.runningAfterEachAttributeReadOf(
              fileSystem, SESSION_MARK_NAME, () -> pointAt(link, otherFolder));

      startOf(leftoversInMemory(pointedElsewhereOnceItReadsASessionsMark.getPath(link.toString())));

      assertThat(Files.readSymbolicLink(link))
          .as("the link was pointed at the other folder while the start deleted")
          .isEqualTo(otherFolder);
      assertThat(movie).hasBinaryContent(MEDIA);
      assertThat(markedFolder.resolve(sessionName)).doesNotExist();
    }
  }

  // The folder that the link is pointed at belongs to another running server process, which holds
  // its own lock. The start holds the lock of the folder that the link named first.
  @Test
  @DisplayName(
      "Should keep another server process's stream sessions when the segment folder's soft link is"
          + " pointed at its folder while the start takes the lock")
  void
      shouldKeepAnotherServerProcesssStreamSessionsWhenTheSegmentFoldersSoftLinkIsPointedAtItsFolderWhileTheStartTakesTheLock()
          throws IOException {
    startOf(segmentFolder);
    var leftover = leftSessionIn(segmentFolder);
    var otherFolder = Files.createDirectory(outside.resolve("segments"));
    startOf(otherFolder);
    var link = Files.createSymbolicLink(machine.resolve("segments"), segmentFolder);
    var pointedAtTheOtherFolderOnceItOpensTheLock =
        ThrowingFileSystemWrapper.runningAfterEachChannelOpenOf(
            FileSystems.getDefault(), LOCK_NAME, () -> pointAt(link, otherFolder));
    try (var otherProcessLock = new SegmentFolderLock()) {
      var otherProcessSession =
          new LocalSegmentStore(otherFolder, otherProcessLock)
              .getOutputDirectory(UUID.randomUUID());

      var outcome =
          startOf(leftoversOf(pointedAtTheOtherFolderOnceItOpensTheLock.getPath(link.toString())));

      assertThat(Files.readSymbolicLink(link))
          .as("the link was pointed at the other folder while the start took the lock")
          .isEqualTo(otherFolder);
      assertThat(otherProcessSession).isDirectory();
      assertThat(leftover).doesNotExist();
      assertThat(outcome).isEqualTo(new Deleted(1, 0));
    }
  }

  // The running process holds the lock of the folder that the link named at its start, so its
  // store keeps writing there.
  @Test
  @DisplayName(
      "Should keep a running server process's stream sessions when its segment folder's soft link is"
          + " pointed at another server process's folder after its start")
  void
      shouldKeepARunningServerProcesssStreamSessionsWhenItsSegmentFoldersSoftLinkIsPointedAtAnotherServerProcesssFolderAfterItsStart()
          throws IOException {
    var otherFolder = Files.createDirectory(outside.resolve("segments"));
    startOf(otherFolder);
    var link = Files.createSymbolicLink(machine.resolve("segments"), segmentFolder);
    try (var runningProcessLock = new SegmentFolderLock()) {
      leftoversOf(link).lock(runningProcessLock).build().delete();
      pointAt(link, otherFolder);
      var runningSession =
          new LocalSegmentStore(link, runningProcessLock).getOutputDirectory(UUID.randomUUID());

      var otherFoldersStart = startOf(otherFolder);
      var firstFoldersStart = startOf(segmentFolder);

      assertThat(runningSession).isDirectory();
      assertThat(otherFoldersStart).isEqualTo(new Deleted(0, 0));
      assertThat(firstFoldersStart).isEqualTo(new Refused(SegmentFolderRefusal.IN_USE));
    }
  }

  // A process whose start ran nothing on the folder, as on the shared folder, holds the lock of the
  // folder that the link named at its store's first write.
  @Test
  @DisplayName(
      "Should keep a running server process's later stream sessions when its segment folder's soft"
          + " link is pointed at another server process's folder after its store's first write")
  void
      shouldKeepARunningServerProcesssLaterStreamSessionsWhenItsSegmentFoldersSoftLinkIsPointedAtAnotherServerProcesssFolderAfterItsStoresFirstWrite()
          throws IOException {
    startOf(segmentFolder);
    var otherFolder = Files.createDirectory(outside.resolve("segments"));
    startOf(otherFolder);
    var link = Files.createSymbolicLink(machine.resolve("segments"), segmentFolder);
    try (var runningProcessLock = new SegmentFolderLock()) {
      var runningStore = new LocalSegmentStore(link, runningProcessLock);
      runningStore.getOutputDirectory(UUID.randomUUID());
      pointAt(link, otherFolder);
      var laterSession = runningStore.getOutputDirectory(UUID.randomUUID());

      var otherFoldersStart = startOf(otherFolder);
      var firstFoldersStart = startOf(segmentFolder);

      assertThat(laterSession).isDirectory();
      assertThat(otherFoldersStart).isEqualTo(new Deleted(0, 0));
      assertThat(firstFoldersStart).isEqualTo(new Refused(SegmentFolderRefusal.IN_USE));
    }
  }

  // On macOS a listing that is open while entries of its folder are deleted skips some of a few
  // hundred entries.
  @Test
  @DisplayName("Should delete every leftover when the segment folder holds hundreds of them")
  void shouldDeleteEveryLeftoverWhenTheSegmentFolderHoldsHundredsOfThem() throws IOException {
    startOf(segmentFolder);
    for (var i = 0; i < 300; i++) {
      leftover(segmentFolder);
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
  void shouldRefuseTheFolderWhenItIsAMediaFolder() {
    startOf(segmentFolder);
    var movie = deletableFileIn(segmentFolder);

    var outcome = startOf(leftoversOf(segmentFolder).mediaFolders(() -> List.of(segmentFolder)));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.MEDIA_FOLDER));
    assertThat(movie).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName("Should refuse the folder when it holds a media folder")
  void shouldRefuseTheFolderWhenItHoldsAMediaFolder() {
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
    var session = leftSessionIn(segments);

    var outcome = startOf(leftoversOf(segments).mediaFolders(() -> List.of(library)));

    assertThat(outcome).isEqualTo(new Deleted(1, 0));
    assertThat(session).doesNotExist();
  }

  @Test
  @DisplayName("Should refuse every folder when the media folders cannot be read")
  void shouldRefuseEveryFolderWhenTheMediaFoldersCannotBeRead() {
    startOf(segmentFolder);
    var session = leftSessionIn(segmentFolder);

    var outcome = startOf(leftoversOf(segmentFolder).mediaFolders(unreadableMediaFolders()));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.UNKNOWN_MEDIA_FOLDERS));
    assertThat(session).isDirectory();
  }

  // No folder above the bind mount's path is the library's, so only the mount table shows where
  // the folder lies.
  @Test
  @DisplayName(
      "Should refuse the folder and create nothing when it is a bind mount of a folder in a library")
  void shouldRefuseTheFolderAndCreateNothingWhenItIsABindMountOfAFolderInALibrary()
      throws IOException {
    var outcome =
        startOf(
            leftoversOf(segmentFolder)
                .mediaFolders(() -> List.of(Path.of("/srv/media/movies")))
                .mountTable(
                    mountTableOf(
                        """
                        22 1 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw
                        41 22 8:1 /srv/media/movies/cache %s rw,relatime shared:1 - ext4 /dev/sda1 rw
                        """,
                        segmentFolder)));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.MEDIA_FOLDER));
    assertThat(entriesOf(segmentFolder)).isEmpty();
  }

  // In a container, the host's /data/media is the library at /media and the host's /data is the
  // segment folder. The container has no path to the host's /data that holds the library.
  @Test
  @DisplayName(
      "Should refuse the folder and keep its files when it is a bind mount of a folder that holds a"
          + " library")
  void shouldRefuseTheFolderAndKeepItsFilesWhenItIsABindMountOfAFolderThatHoldsALibrary() {
    startOf(segmentFolder);
    var file = deletableFileIn(segmentFolder);

    var outcome =
        startOf(
            leftoversOf(segmentFolder)
                .mediaFolders(() -> List.of(Path.of("/media")))
                .mountTable(
                    mountTableOf(
                        """
                        617 553 0:52 / / rw,relatime master:301 - overlay overlay rw,lowerdir=/l/A:/l/B,upperdir=/u,workdir=/w
                        640 617 8:1 /data/media /media ro,relatime - ext4 /dev/sda1 rw
                        641 617 8:1 /data %s rw,relatime - ext4 /dev/sda1 rw
                        """,
                        segmentFolder)));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.MEDIA_FOLDER));
    assertThat(file).hasBinaryContent(MEDIA);
  }

  // The library's disk is mounted twice: on /mnt/disk, and on the folder that holds the segment
  // folder. The library /mnt/disk/<name> is the segment folder under its other path.
  @Test
  @DisplayName(
      "Should refuse the folder and keep its files when a second mount of a library's file system"
          + " leads to it")
  void shouldRefuseTheFolderAndKeepItsFilesWhenASecondMountOfALibrarysFileSystemLeadsToIt() {
    startOf(segmentFolder);
    var file = deletableFileIn(segmentFolder);
    var library = Path.of("/mnt/disk").resolve(segmentFolder.getFileName().toString());

    var outcome =
        startOf(
            leftoversOf(segmentFolder)
                .mediaFolders(() -> List.of(library))
                .mountTable(
                    mountTableOf(
                        """
                        22 1 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw
                        45 22 8:17 / /mnt/disk rw,relatime shared:5 - xfs /dev/sdb1 rw
                        46 22 8:17 / %s rw,relatime shared:6 - xfs /dev/sdb1 rw
                        """,
                        segmentFolder.getParent())));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.MEDIA_FOLDER));
    assertThat(file).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName(
      "Should delete leftovers when the folder and a library lie in different subvolumes of one"
          + " file system")
  void shouldDeleteLeftoversWhenTheFolderAndALibraryLieInDifferentSubvolumesOfOneFileSystem() {
    startOf(segmentFolder);
    var session = leftSessionIn(segmentFolder);

    var outcome =
        startOf(
            leftoversOf(segmentFolder)
                .mediaFolders(() -> List.of(Path.of("/srv/media/movies")))
                .mountTable(
                    mountTableOf(
                        """
                        22 1 0:31 /@ / rw,relatime shared:1 - btrfs /dev/nvme0n1p2 rw,ssd,subvolid=256,subvol=/@
                        30 22 0:31 /@media /srv/media rw,relatime shared:2 - btrfs /dev/nvme0n1p2 rw,ssd,subvolid=257,subvol=/@media
                        """)));

    assertThat(outcome).isEqualTo(new Deleted(1, 0));
    assertThat(session).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should delete leftovers when the folder lies in a container's own file system and a library"
          + " is a bind mount from the host")
  void shouldDeleteLeftoversWhenTheFolderLiesInAContainersOwnFileSystemAndALibraryIsABindMount() {
    startOf(segmentFolder);
    var session = leftSessionIn(segmentFolder);

    var outcome =
        startOf(
            leftoversOf(segmentFolder)
                .mediaFolders(() -> List.of(Path.of("/media")))
                .mountTable(
                    mountTableOf(
                        """
                        617 553 0:52 / / rw,relatime master:301 - overlay overlay rw,lowerdir=/l/A:/l/B,upperdir=/u,workdir=/w
                        640 617 8:1 /srv/media /media ro,relatime - ext4 /dev/sda1 rw
                        """)));

    assertThat(outcome).isEqualTo(new Deleted(1, 0));
    assertThat(session).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should refuse the folder and keep its files when the mount table does not show which mount"
          + " holds it")
  void shouldRefuseTheFolderAndKeepItsFilesWhenTheMountTableDoesNotShowWhichMountHoldsIt() {
    startOf(segmentFolder);
    var file = deletableFileIn(segmentFolder);

    var outcome =
        startOf(
            leftoversOf(segmentFolder)
                .mountTable(
                    mountTableOf(
                        """
                        41 22 0:41 / /run rw,nosuid,nodev shared:2 - tmpfs tmpfs rw
                        """)));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.UNKNOWN_MOUNT_POINTS));
    assertThat(file).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName("Should refuse the folder when it is the home folder")
  void shouldRefuseTheFolderWhenItIsTheHomeFolder() {
    startOf(segmentFolder);
    var file = deletableFileIn(segmentFolder);

    var outcome = startOf(leftoversOf(segmentFolder).homeFolder(segmentFolder));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.HOME_FOLDER));
    assertThat(file).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName("Should refuse the folder when it holds the home folder")
  void shouldRefuseTheFolderWhenItHoldsTheHomeFolder() {
    startOf(segmentFolder);
    var file = deletableFileIn(segmentFolder);
    var home = segmentFolder.resolve("home").resolve("streamarr");

    var outcome = startOf(leftoversOf(segmentFolder).homeFolder(home));

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.HOME_FOLDER));
    assertThat(file).hasBinaryContent(MEDIA);
  }

  @Test
  @DisplayName("Should refuse the folder and create nothing when it is the root directory")
  void shouldRefuseTheFolderAndCreateNothingWhenItIsTheRootDirectory() throws IOException {
    try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
      var top = fileSystem.getPath("/");

      var outcome =
          startOf(
              SegmentFolderLeftovers.builder()
                  .segmentFolder(top)
                  .homeFolder(fileSystem.getPath("/home/streamarr"))
                  .mediaFolders(() -> List.of(fileSystem.getPath("/media")))
                  .mountTable(fileSystem.getPath("/proc/self/mountinfo")));

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

  @Test
  @DisplayName("Should reject a missing mount table when created")
  void shouldRejectAMissingMountTableWhenCreated() {
    assertThatNullPointerException()
        .isThrownBy(() -> leftoversOf(segmentFolder).mountTable(null).build());
  }

  // The start of a server process whose home folder and media folders lie elsewhere. It reads them
  // and its mount table through the segment folder's file system, which a test may wrap.
  private SegmentFolderLeftovers.SegmentFolderLeftoversBuilder leftoversOf(Path folder) {
    var fileSystem = folder.getFileSystem();
    return SegmentFolderLeftovers.builder()
        .segmentFolder(folder)
        .homeFolder(fileSystem.getPath(outside.resolve("home").toString()))
        .mediaFolders(() -> List.of(fileSystem.getPath(outside.resolve("media").toString())))
        .mountTable(fileSystem.getPath(mountTableOf(ROOT_FILE_SYSTEM).toString()))
        .lock(new SegmentFolderLock());
  }

  // The start of a server process on a file system in memory, which holds the process's mount
  // table. Its home folder and media folders lie elsewhere.
  private static SegmentFolderLeftovers.SegmentFolderLeftoversBuilder leftoversInMemory(
      Path folder) {
    var fileSystem = folder.getFileSystem();
    return SegmentFolderLeftovers.builder()
        .segmentFolder(folder)
        .homeFolder(fileSystem.getPath("/home/streamarr"))
        .mediaFolders(() -> List.of(fileSystem.getPath("/media")))
        .mountTable(fileSystem.getPath("/proc/self/mountinfo"));
  }

  // A mount table in the format of /proc/self/mountinfo. Each %s in the lines takes the real path
  // of one of the mount points, escaped as the kernel escapes it.
  private Path mountTableOf(String lines, Path... mountPoints) {
    var escapedMountPoints =
        Arrays.stream(mountPoints).map(SegmentFolderLeftoversTest::escapedRealPath).toArray();
    return tableAt(
        machine.resolve("mountinfo-" + UUID.randomUUID()), lines.formatted(escapedMountPoints));
  }

  private static Path tableAt(Path table, String lines) {
    try {
      return Files.writeString(table, lines);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // The kernel writes a space, tab, newline or backslash in a path as a backslash and three octal
  // digits.
  private static String escapedRealPath(Path path) {
    try {
      return path.toRealPath()
          .toString()
          .replace("\\", "\\134")
          .replace(" ", "\\040")
          .replace("\t", "\\011")
          .replace("\n", "\\012");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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

  // What the store of a server process wrote before the process ended: the operating system
  // released the process's lock, and what the store wrote stays.
  private <T> T leftByAnEndedProcess(StoreWrites<T> writes) {
    return leftByAnEndedProcess(segmentFolder, writes);
  }

  private static <T> T leftByAnEndedProcess(Path folder, StoreWrites<T> writes) {
    try (var lock = new SegmentFolderLock()) {
      return writes.apply(new LocalSegmentStore(folder, lock));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path leftSessionIn(Path folder) {
    return leftByAnEndedProcess(folder, store -> store.getOutputDirectory(UUID.randomUUID()));
  }

  // A file in a stream session's directory that a store wrote, which a start that deletes would
  // remove with the directory.
  private static Path deletableFileIn(Path folder) {
    return leftByAnEndedProcess(
        folder,
        store ->
            Files.write(store.getOutputDirectory(UUID.randomUUID()).resolve("movie.mkv"), MEDIA));
  }

  private static Set<Path> entriesOf(Path folder) throws IOException {
    try (var entries = Files.list(folder)) {
      return entries.collect(Collectors.toSet());
    }
  }

  private static Supplier<List<Path>> unreadableMediaFolders() {
    return () -> {
      throw new IllegalStateException("The library table cannot be read");
    };
  }

  @FunctionalInterface
  private interface StoreWrites<T> {
    T apply(LocalSegmentStore store) throws IOException;
  }

  // A stream session's directory with a segment in a variant directory.
  private static Path leftover(Path folder) {
    return leftByAnEndedProcess(
        folder,
        store -> {
          var sessionId = UUID.randomUUID();
          Files.write(store.getOutputDirectory(sessionId, "720p").resolve("0.m4s"), SEGMENT);
          return store.getOutputDirectory(sessionId);
        });
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

  private static void pointAt(Path link, Path folder) {
    try {
      Files.delete(link);
      Files.createSymbolicLink(link, folder);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
