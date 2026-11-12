package com.streamarr.server.services.streaming.local;

import com.google.common.io.MoreFiles;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Deleted;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Refused;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
   * <p>It refuses a folder that is the top of a file system, that is the home folder or lies above
   * it, that is a media folder or lies above or below one, or that a file system is mounted in, and
   * it creates nothing there. It refuses every folder when the media folders or the mount table
   * cannot be read.
   *
   * <p>It deletes only in a folder that carries its mark, and it marks only a folder that holds no
   * other entry. There it deletes a stream session's directory only when a store marked the
   * directory as its own. A folder that held something else first therefore keeps everything. The
   * mark holds the path of the folder that it marks, so a mark that was copied into another folder
   * marks nothing there. It never throws: what it cannot delete stays.
   *
   * <p>It marks the folder and deletes only while it holds the folder's lock alone, so only while
   * no other running server process has written into the folder.
   */
  public SegmentFolderLeftoversOutcome delete() {
    try {
      var refusal = refusal();
      if (refusal.isPresent()) {
        return refused(refusal.get());
      }

      Files.createDirectories(segmentFolder);
      // Before the lock's file, so that a folder that holds something else gets nothing.
      if (!isMarkable()) {
        return refused(SegmentFolderRefusal.UNMARKED_FOLDER);
      }

      return lock.whileAlone(segmentFolder, this::markAndDeleteEachLeftover)
          .orElseGet(() -> refused(SegmentFolderRefusal.IN_USE));
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

  private Optional<SegmentFolderRefusal> refusal() throws IOException {
    var folder = resolved(segmentFolder);
    if (folder.getParent() == null) {
      return Optional.of(SegmentFolderRefusal.FILE_SYSTEM_ROOT);
    }

    if (resolved(homeFolder).startsWith(folder)) {
      return Optional.of(SegmentFolderRefusal.HOME_FOLDER);
    }

    var mediaFolderRefusal = mediaFolderRefusal(folder);
    if (mediaFolderRefusal.isPresent()) {
      return mediaFolderRefusal;
    }

    return mountPointRefusal(folder);
  }

  private Optional<SegmentFolderRefusal> mediaFolderRefusal(Path folder) throws IOException {
    List<Path> folders;
    try {
      folders = mediaFolders.get();
    } catch (RuntimeException e) {
      log.warn("Failed to read the media folders", e);
      return Optional.of(SegmentFolderRefusal.UNKNOWN_MEDIA_FOLDERS);
    }

    for (var mediaFolder : folders) {
      var media = resolved(mediaFolder);
      // Path.startsWith compares whole names, so /media/movies2 does not start with /media/movies.
      if (media.startsWith(folder) || folder.startsWith(media)) {
        return Optional.of(SegmentFolderRefusal.MEDIA_FOLDER);
      }
    }

    return Optional.empty();
  }

  // A delete would empty a file system that is mounted in the folder. A second mount of the folder's
  // own file system crosses no device boundary, so only the mount table shows every mount.
  private Optional<SegmentFolderRefusal> mountPointRefusal(Path folder) {
    List<Path> mountPoints;
    try {
      mountPoints = MountPoints.in(mountTable);
    } catch (IOException | RuntimeException e) {
      log.warn("Failed to read the mount table {}", mountTable, e);
      return Optional.of(SegmentFolderRefusal.UNKNOWN_MOUNT_POINTS);
    }

    var mountedInside =
        mountPoints.stream()
            .anyMatch(mountPoint -> mountPoint.startsWith(folder) && !mountPoint.equals(folder));
    if (mountedInside) {
      return Optional.of(SegmentFolderRefusal.MOUNT_POINT_INSIDE);
    }

    return Optional.empty();
  }

  // The path with the soft links of its existing folders resolved. A folder that does not exist
  // yet has no real path, and a soft link above it can lead into a folder that the path does not
  // name.
  private static Path resolved(Path path) throws IOException {
    var absolute = path.toAbsolutePath();
    var existing = absolute;
    while (existing.getParent() != null && Files.notExists(existing)) {
      existing = existing.getParent();
    }

    return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
  }

  // Only one start at a time holds the lock alone, so two starts never write the mark at once.
  private SegmentFolderLeftoversOutcome markAndDeleteEachLeftover() throws IOException {
    if (isMarked()) {
      return deleteEachLeftover();
    }

    if (!holdsNoOtherEntry()) {
      return refused(SegmentFolderRefusal.UNMARKED_FOLDER);
    }

    writeMark();
    return deleteEachLeftover();
  }

  // Whether the folder carries its mark, or holds no other entry, so that a start may mark it.
  private boolean isMarkable() throws IOException {
    return isMarked() || holdsNoOtherEntry();
  }

  // Compared as bytes, so that a mark which is not text reads as the mark of another folder. A
  // mark of another size is not read, so that no mark is too large to read.
  private boolean isMarked() throws IOException {
    if (!Files.isRegularFile(mark(), LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }

    var markedFolder = markedFolder();
    return Files.size(mark()) == markedFolder.length
        && Arrays.equals(Files.readAllBytes(mark()), markedFolder);
  }

  private byte[] markedFolder() throws IOException {
    return segmentFolder.toRealPath().toString().getBytes(StandardCharsets.UTF_8);
  }

  private Path mark() {
    return segmentFolder.resolve(MARK_NAME);
  }

  // A mark and a lock alone claim nothing for another owner: an earlier process may have ended
  // while it wrote the mark.
  private boolean holdsNoOtherEntry() throws IOException {
    var lockFile = segmentFolder.resolve(SegmentFolderLock.NAME);
    try (var others =
        Files.newDirectoryStream(
            segmentFolder, entry -> !entry.equals(mark()) && !entry.equals(lockFile))) {
      return !others.iterator().hasNext();
    }
  }

  // Replaces whatever holds the mark's place. CREATE_NEW fails on a soft link there, where a plain
  // write would overwrite the file that the link points to.
  private void writeMark() throws IOException {
    Files.deleteIfExists(mark());
    Files.write(mark(), markedFolder(), StandardOpenOption.CREATE_NEW);
  }

  private Deleted deleteEachLeftover() throws IOException {
    var leftovers = new ArrayList<Leftover>();
    for (var entry : listedEntries()) {
      leftovers.add(deleteIfLeftover(entry));
    }

    return new Deleted(
        Collections.frequency(leftovers, Leftover.DELETED),
        Collections.frequency(leftovers, Leftover.REMAINING));
  }

  // Read to its end before the first delete. MoreFiles.deleteRecursively opens the folder again
  // for each entry, and on macOS a listing that is open meanwhile then skips entries.
  private List<Path> listedEntries() throws IOException {
    var listed = new ArrayList<Path>();
    try (var entries = Files.newDirectoryStream(segmentFolder)) {
      entries.forEach(listed::add);
    }

    return listed;
  }

  private Leftover deleteIfLeftover(Path entry) {
    try {
      var name = entry.getFileName().toString();
      // A soft link is neither a directory nor a regular file here, so it stays.
      var attributes =
          Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isDirectory()
          && SESSION_DIRECTORY_NAME.matcher(name).matches()
          && Files.isRegularFile(
              entry.resolve(LocalSegmentStore.SESSION_MARK_NAME), LinkOption.NOFOLLOW_LINKS)) {
        // Deletes a soft link itself, never what it points to.
        MoreFiles.deleteRecursively(entry);
        return Leftover.DELETED;
      }

      if (attributes.isRegularFile() && PreparedSegmentFile.isUploadName(name)) {
        Files.delete(entry);
        return Leftover.DELETED;
      }

      return Leftover.NOT_A_LEFTOVER;
    } catch (IOException e) {
      log.warn("Failed to delete a leftover of the segment folder: {}", entry, e);
      return Leftover.REMAINING;
    }
  }

  private enum Leftover {
    DELETED,
    REMAINING,
    NOT_A_LEFTOVER
  }
}
