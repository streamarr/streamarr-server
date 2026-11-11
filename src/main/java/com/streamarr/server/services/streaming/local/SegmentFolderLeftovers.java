package com.streamarr.server.services.streaming.local;

import com.google.common.io.MoreFiles;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * What earlier server processes left in a segment folder that only this server uses: their stream
 * sessions' directories and their unpublished uploads. Those stream sessions ended with their
 * process, so no segment store deletes them.
 */
@Slf4j
@RequiredArgsConstructor
public class SegmentFolderLeftovers {

  private static final String MARK_NAME = ".streamarr-segment-folder";

  // LocalSegmentStore names a stream session's directory after the session's UUID.
  private static final Pattern SESSION_DIRECTORY_NAME =
      Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

  // PreparedSegmentFile gives an unpublished upload this prefix and suffix.
  private static final String UPLOAD_PREFIX = ".upload-";
  private static final String UPLOAD_SUFFIX = ".tmp";

  @NonNull private final Path segmentFolder;

  /**
   * Deletes the leftovers. Call it before the folder's segment store exists, so that the folder
   * holds nothing of a running store.
   *
   * <p>It deletes only in a folder that carries its mark, and it marks only a folder that is empty.
   * A folder that held something else first therefore keeps everything. The mark holds the path of
   * the folder that it marks, so a mark that was copied into another folder marks nothing there. It
   * never throws: what it cannot delete stays.
   */
  public void delete() {
    try {
      Files.createDirectories(segmentFolder);
      if (isMarked()) {
        deleteEachLeftover();
        return;
      }

      if (isEmpty()) {
        Files.writeString(mark(), markedFolder());
        return;
      }

      log.warn(
          "The segment folder {} holds entries but no mark of its own, so the server deletes no"
              + " leftovers there. Give the server an empty folder of its own.",
          segmentFolder);
    } catch (IOException | DirectoryIteratorException e) {
      log.warn("Failed to delete the leftovers in the segment folder {}", segmentFolder, e);
    }
  }

  private boolean isMarked() throws IOException {
    return Files.isRegularFile(mark(), LinkOption.NOFOLLOW_LINKS)
        && Files.readString(mark()).equals(markedFolder());
  }

  private String markedFolder() throws IOException {
    return segmentFolder.toRealPath().toString();
  }

  private Path mark() {
    return segmentFolder.resolve(MARK_NAME);
  }

  private boolean isEmpty() throws IOException {
    try (var entries = Files.newDirectoryStream(segmentFolder)) {
      return !entries.iterator().hasNext();
    }
  }

  private void deleteEachLeftover() throws IOException {
    try (var entries = Files.newDirectoryStream(segmentFolder)) {
      for (var entry : entries) {
        deleteIfLeftover(entry);
      }
    }
  }

  private void deleteIfLeftover(Path entry) {
    try {
      var name = entry.getFileName().toString();
      // A soft link is neither a directory nor a regular file here, so it stays.
      var attributes =
          Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isDirectory() && SESSION_DIRECTORY_NAME.matcher(name).matches()) {
        // Deletes a soft link itself, never what it points to.
        MoreFiles.deleteRecursively(entry);
        return;
      }

      if (attributes.isRegularFile() && isUploadName(name)) {
        Files.delete(entry);
      }
    } catch (IOException e) {
      log.warn("Failed to delete a leftover of the segment folder: {}", entry, e);
    }
  }

  private static boolean isUploadName(String name) {
    return name.startsWith(UPLOAD_PREFIX) && name.endsWith(UPLOAD_SUFFIX);
  }
}
