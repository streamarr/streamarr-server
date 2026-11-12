package com.streamarr.server.services.streaming.local;

import com.streamarr.server.exceptions.UnknownFolderLocationException;
import com.streamarr.server.exceptions.UnmarkedFolderException;
import com.streamarr.server.exceptions.UnsupportedSegmentFolderException;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Deleted;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Refused;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * What earlier server processes left in a segment folder that only this server uses: their stream
 * sessions' directories and their unpublished uploads. Those stream sessions ended with their
 * process, so no segment store deletes them.
 */
@Slf4j
@Builder
public class SegmentFolderLeftovers {

  private static final String MARK_NAME = ".streamarr-segment-folder";

  // LocalSegmentStore names a stream session's directory after the session's UUID.
  private static final Pattern SESSION_DIRECTORY_NAME =
      Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

  @NonNull private final Path segmentFolder;

  @NonNull private final Path homeFolder;

  // The media root and every library's folder, read when the delete runs.
  @NonNull private final Supplier<List<Path>> mediaFolders;

  // The mount table of this server process, in the format of /proc/self/mountinfo.
  @NonNull private final Path mountTable;

  // The lock of this server process, which its segment store holds shared once it writes.
  @NonNull private final SegmentFolderLock lock;

  /**
   * Deletes the leftovers. Call it before the folder's segment store exists, so that the folder
   * holds nothing of a running store.
   *
   * <p>It refuses a folder that is a root directory, that is the home folder or lies above it, that
   * is a media folder or lies above or below one under any of their paths that the mount table
   * shows, or that a file system is mounted in, and it creates nothing there. It refuses every
   * folder when the media folders or the mount table cannot be read, or the mount table does not
   * show where the folders lie.
   *
   * <p>It deletes only in a folder that carries its mark, and it marks only a folder that holds no
   * other entry. There it enters a stream session's directory, and each folder in it, only when a
   * store marked that folder as its own. A folder without the store's mark stays whole, and so does
   * the stream session's directory that holds it, which the outcome counts as remaining. A folder
   * that held something else first therefore keeps everything. The mark holds the path of the
   * folder that it marks, so a mark that was copied into another folder marks nothing there. It
   * never throws: what it cannot delete stays.
   *
   * <p>It marks the folder and deletes only while it holds the folder's lock alone, so only while
   * no other running server process has written into the folder.
   */
  public SegmentFolderLeftoversOutcome delete() {
    try {
      // Resolved once: a soft link on the configured path that is pointed elsewhere meanwhile leads
      // no later step out of the folder that the refusals checked and the lock guards.
      var folder = resolved(segmentFolder);
      var refusal = refusal(folder);
      if (refusal.isPresent()) {
        return refused(refusal.get());
      }

      Files.createDirectories(folder);
      return markAndDeleteIn(folder);
    } catch (IOException | RuntimeException e) {
      log.warn("Failed to delete the leftovers in the segment folder {}", segmentFolder, e);
      return new Refused(SegmentFolderRefusal.UNREADABLE_FOLDER);
    }
  }

  private Refused refused(SegmentFolderRefusal refusal) {
    log.warn(
        "The server deletes no leftovers in the segment folder {}: {}. Give the server an empty"
            + " folder of its own.",
        segmentFolder,
        refusal);
    return new Refused(refusal);
  }

  private Optional<SegmentFolderRefusal> refusal(Path folder) throws IOException {
    if (folder.getParent() == null) {
      return Optional.of(SegmentFolderRefusal.FILE_SYSTEM_ROOT);
    }

    if (isAtOrAbove(folder, homeFolder)) {
      return Optional.of(SegmentFolderRefusal.HOME_FOLDER);
    }

    var folders = readMediaFolders();
    if (folders.isEmpty()) {
      return Optional.of(SegmentFolderRefusal.UNKNOWN_MEDIA_FOLDERS);
    }

    var mediaFolderRefusal = mediaFolderRefusal(folder, folders.get());
    if (mediaFolderRefusal.isPresent()) {
      return mediaFolderRefusal;
    }

    return mountRefusal(folder, folders.get());
  }

  // Read once per start, so that its checks compare the same folders.
  private Optional<List<Path>> readMediaFolders() {
    try {
      return Optional.of(mediaFolders.get());
    } catch (RuntimeException e) {
      log.warn("Failed to read the media folders", e);
      return Optional.empty();
    }
  }

  private static Optional<SegmentFolderRefusal> mediaFolderRefusal(
      Path folder, List<Path> mediaFolders) throws IOException {
    for (var mediaFolder : mediaFolders) {
      if (isAtOrAbove(folder, mediaFolder) || isAtOrAbove(mediaFolder, folder)) {
        return Optional.of(SegmentFolderRefusal.MEDIA_FOLDER);
      }
    }

    return Optional.empty();
  }

  // A delete would empty a file system that is mounted in the folder. A second mount of the
  // folder's own file system crosses no device boundary, so only the mount table shows every
  // mount.
  private Optional<SegmentFolderRefusal> mountRefusal(Path folder, List<Path> mediaFolders)
      throws IOException {
    MountTable mounts;
    try {
      mounts = MountTable.read(mountTable);
    } catch (IOException | RuntimeException e) {
      log.warn("Failed to read the mount table {}", mountTable, e);
      return Optional.of(SegmentFolderRefusal.UNKNOWN_MOUNT_POINTS);
    }

    for (var mountPoint : mounts.mountPoints()) {
      if (liesInside(mountPoint, folder)) {
        return Optional.of(SegmentFolderRefusal.MOUNT_POINT_INSIDE);
      }
    }

    return mediaFolderLocationRefusal(folder, mediaFolders, mounts);
  }

  // The table spells a path as the kernel does. A case-insensitive file system lets the kernel's
  // spelling of the folder differ from its real path in its names but not in their number, so the
  // mount point's folder at the folder's depth is compared with the folder as a file. That spelling
  // leads through the folders that the start searched to reach the folder, so a path that the start
  // cannot search, or that does not exist, is not the folder.
  private static boolean liesInside(Path mountPoint, Path folder) throws IOException {
    if (mountPoint.getNameCount() <= folder.getNameCount()) {
      return false;
    }

    var atTheFoldersDepth =
        mountPoint.getRoot().resolve(mountPoint.subpath(0, folder.getNameCount()));
    try {
      return Files.isSameFile(atTheFoldersDepth, folder);
    } catch (AccessDeniedException | NoSuchFileException _) {
      return false;
    }
  }

  // A bind mount of a folder in a library, or a second mount of a library's file system, gives the
  // folder a path below no library's path. The mount table shows where each path really leads.
  private Optional<SegmentFolderRefusal> mediaFolderLocationRefusal(
      Path folder, List<Path> mediaFolders, MountTable mounts) throws IOException {
    var resolvedMediaFolders = new ArrayList<Path>();
    for (var mediaFolder : mediaFolders) {
      resolvedMediaFolders.add(resolved(mediaFolder));
    }

    try {
      if (resolvedMediaFolders.stream()
          .anyMatch(mediaFolder -> mounts.liesInOrHolds(folder, mediaFolder))) {
        return Optional.of(SegmentFolderRefusal.MEDIA_FOLDER);
      }
    } catch (UnknownFolderLocationException e) {
      log.warn("Failed to tell from the mount table {} where folders lie", mountTable, e);
      return Optional.of(SegmentFolderRefusal.UNKNOWN_MOUNT_POINTS);
    }

    return Optional.empty();
  }

  // Whether `upper` is `lower` or a folder above it. Path.startsWith compares whole names, so
  // /media/movies2 does not lie in /media/movies. The folders are compared as files too: a bind
  // mount, or a spelling that a case-insensitive file system folds, is a second name for a folder
  // that no real path resolves.
  private static boolean isAtOrAbove(Path upper, Path lower) throws IOException {
    var upperPath = resolved(upper);
    var lowerPath = resolved(lower);
    if (lowerPath.startsWith(upperPath)) {
      return true;
    }

    if (Files.notExists(upperPath)) {
      return false;
    }

    for (var folder = nearestExisting(lowerPath); folder != null; folder = folder.getParent()) {
      if (Files.isSameFile(folder, upperPath)) {
        return true;
      }
    }

    return false;
  }

  // The path with the soft links of its existing folders resolved. A folder that does not exist
  // yet has no real path, and a soft link above it can lead into a folder that the path does not
  // name.
  private static Path resolved(Path path) throws IOException {
    var absolute = path.toAbsolutePath();
    var existing = nearestExisting(absolute);
    return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
  }

  private static Path nearestExisting(Path absolute) {
    var existing = absolute;
    while (existing.getParent() != null && Files.notExists(existing)) {
      existing = existing.getParent();
    }

    return existing;
  }

  // The start locks, marks and deletes through one handle of the folder, never through the folder's
  // path again: a soft link or folder that takes the folder's place meanwhile cannot lead it into
  // another folder.
  private SegmentFolderLeftoversOutcome markAndDeleteIn(Path folder) throws IOException {
    try (var opened = openedFolder(folder)) {
      // Before the lock's file, so that a folder that holds something else gets nothing.
      if (!isMarkable(opened, folder)) {
        return refused(SegmentFolderRefusal.UNMARKED_FOLDER);
      }

      return lock.whileAlone(opened, folder, () -> markAndDeleteEachLeftover(opened, folder))
          .orElseGet(() -> refused(SegmentFolderRefusal.IN_USE));
    }
  }

  // Only one start at a time holds the lock alone, so two starts never write the mark at once.
  private SegmentFolderLeftoversOutcome markAndDeleteEachLeftover(
      SecureDirectoryStream<Path> opened, Path folder) throws IOException {
    if (isMarked(opened, folder)) {
      return deleteEachLeftover(opened, folder);
    }

    if (!holdsNoOtherEntry(opened, folder)) {
      return refused(SegmentFolderRefusal.UNMARKED_FOLDER);
    }

    writeMark(opened, folder);
    // The folder held no other entry, so it holds no leftover.
    return new Deleted(0, 0);
  }

  // Whether the folder carries its mark, or holds no other entry, so that a start may mark it.
  private static boolean isMarkable(SecureDirectoryStream<Path> opened, Path folder)
      throws IOException {
    return isMarked(opened, folder) || holdsNoOtherEntry(opened, folder);
  }

  private SecureDirectoryStream<Path> openedFolder(Path folder) throws IOException {
    var opened = Files.newDirectoryStream(folder);
    if (!(opened instanceof SecureDirectoryStream<Path> secureFolder)) {
      opened.close();
      throw new UnsupportedSegmentFolderException(segmentFolder);
    }

    return secureFolder;
  }

  // Compared as bytes, so that a mark which is not text reads as the mark of another folder. At
  // most one byte more than the folder's path is read, so that no mark is too large to read.
  private static boolean isMarked(SecureDirectoryStream<Path> opened, Path folder)
      throws IOException {
    var mark = folder.getFileSystem().getPath(MARK_NAME);
    if (!isRegularFile(opened, mark)) {
      return false;
    }

    var markedFolder = markedFolder(folder);
    try (var markBytes =
        Channels.newInputStream(
            opened.newByteChannel(
                mark, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
      return Arrays.equals(markBytes.readNBytes(markedFolder.length + 1), markedFolder);
    }
  }

  // The folder's real path as the start resolved it, never resolved again: a folder that takes its
  // place through a soft link carries a mark with another path.
  private static byte[] markedFolder(Path folder) {
    return folder.toString().getBytes(StandardCharsets.UTF_8);
  }

  // A mark and a lock alone claim nothing for another owner: an earlier process may have ended
  // while it wrote the mark.
  private static boolean holdsNoOtherEntry(SecureDirectoryStream<Path> opened, Path folder)
      throws IOException {
    return namesInFolder(opened, folder).stream()
        .map(Path::toString)
        .allMatch(name -> name.equals(MARK_NAME) || name.equals(SegmentFolderLock.NAME));
  }

  // Replaces whatever holds the mark's place. CREATE_NEW fails on a soft link there, where a plain
  // write would overwrite the file that the link points to.
  private static void writeMark(SecureDirectoryStream<Path> opened, Path folder)
      throws IOException {
    var mark = folder.getFileSystem().getPath(MARK_NAME);
    try {
      opened.deleteFile(mark);
    } catch (NoSuchFileException _) {
      // There was no mark to replace.
    }

    try (var markFile =
        Channels.newOutputStream(
            opened.newByteChannel(
                mark, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)))) {
      markFile.write(markedFolder(folder));
    }
  }

  private Deleted deleteEachLeftover(SecureDirectoryStream<Path> opened, Path folder)
      throws IOException {
    var leftovers = new ArrayList<Leftover>();
    for (var name : namesInFolder(opened, folder)) {
      leftovers.add(deleteIfLeftover(opened, name));
    }

    return new Deleted(
        Collections.frequency(leftovers, Leftover.DELETED),
        Collections.frequency(leftovers, Leftover.REMAINING));
  }

  private Leftover deleteIfLeftover(SecureDirectoryStream<Path> folder, Path name) {
    try {
      // A soft link is neither a directory nor a regular file here, so it stays.
      var attributes = attributesOf(folder, name);
      if (attributes.isDirectory() && SESSION_DIRECTORY_NAME.matcher(name.toString()).matches()) {
        return deleteIfMarkedSession(folder, name);
      }

      if (attributes.isRegularFile() && PreparedSegmentFile.isUploadName(name.toString())) {
        folder.deleteFile(name);
        return Leftover.DELETED;
      }

      return Leftover.NOT_A_LEFTOVER;
    } catch (IOException e) {
      log.warn(
          "Failed to delete a leftover of the segment folder: {}", segmentFolder.resolve(name), e);
      return Leftover.REMAINING;
    }
  }

  // The directory opens without following a soft link, and its mark is checked through the handle
  // that deletes it.
  private static Leftover deleteIfMarkedSession(SecureDirectoryStream<Path> folder, Path name)
      throws IOException {
    try (var session = folder.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
      if (!isMarkedByAStore(session, name)) {
        return Leftover.NOT_A_LEFTOVER;
      }

      deleteEntriesThenMark(session, name);
    }

    folder.deleteDirectory(name);
    return Leftover.DELETED;
  }

  // The store marks each folder that it creates. The mark is read through the handle that deletes
  // the folder's entries. A file system mounted on the folder shows its own root through that
  // handle, which carries no mark, and a mount made once the handle is open does not change what
  // the handle reaches.
  private static boolean isMarkedByAStore(SecureDirectoryStream<Path> directory, Path name)
      throws IOException {
    return isRegularFile(directory, storeMark(name));
  }

  // The mark goes last, so that a later start still deletes the rest of a directory that this start
  // could not delete whole.
  private static void deleteEntriesThenMark(SecureDirectoryStream<Path> directory, Path name)
      throws IOException {
    var mark = storeMark(name);
    deleteEach(
        directory, namesIn(directory).stream().filter(entry -> !entry.equals(mark)).toList());
    directory.deleteFile(mark);
  }

  private static Path storeMark(Path name) {
    return name.getFileSystem().getPath(LocalSegmentStore.SESSION_MARK_NAME);
  }

  // Goes on past an entry that it cannot delete, so that it frees what it can, then throws.
  private static void deleteEach(SecureDirectoryStream<Path> directory, List<Path> names)
      throws IOException {
    var failures = new ArrayList<IOException>();
    for (var name : names) {
      try {
        delete(directory, name);
      } catch (IOException e) {
        failures.add(e);
      }
    }

    if (!failures.isEmpty()) {
      var failure = failures.getFirst();
      failures.stream().skip(1).forEach(failure::addSuppressed);
      throw failure;
    }
  }

  // Deletes a soft link itself, never what it points to. A folder without the store's mark stays
  // whole, and so does the stream session's directory that holds it.
  private static void delete(SecureDirectoryStream<Path> directory, Path name) throws IOException {
    if (!attributesOf(directory, name).isDirectory()) {
      directory.deleteFile(name);
      return;
    }

    try (var subdirectory = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
      if (!isMarkedByAStore(subdirectory, name)) {
        throw new UnmarkedFolderException(name);
      }

      deleteEntriesThenMark(subdirectory, name);
    }

    directory.deleteDirectory(name);
  }

  // A directory stream lists its entries only once, so each listing of the folder opens it again
  // through its handle.
  private static List<Path> namesInFolder(SecureDirectoryStream<Path> opened, Path folder)
      throws IOException {
    try (var listing =
        opened.newDirectoryStream(folder.getFileSystem().getPath("."), LinkOption.NOFOLLOW_LINKS)) {
      return namesIn(listing);
    }
  }

  // Read to its end before the first delete: on macOS a listing that is open while entries of its
  // directory are deleted skips some of them.
  private static List<Path> namesIn(SecureDirectoryStream<Path> directory) {
    var names = new ArrayList<Path>();
    directory.forEach(entry -> names.add(entry.getFileName()));
    return names;
  }

  private static boolean isRegularFile(SecureDirectoryStream<Path> directory, Path name)
      throws IOException {
    try {
      return attributesOf(directory, name).isRegularFile();
    } catch (NoSuchFileException _) {
      return false;
    }
  }

  private static BasicFileAttributes attributesOf(SecureDirectoryStream<Path> directory, Path name)
      throws IOException {
    return directory
        .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
        .readAttributes();
  }

  private enum Leftover {
    DELETED,
    REMAINING,
    NOT_A_LEFTOVER
  }
}
