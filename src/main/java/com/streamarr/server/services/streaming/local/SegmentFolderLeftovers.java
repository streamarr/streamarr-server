package com.streamarr.server.services.streaming.local;

import com.google.common.io.MoreFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

  @NonNull private final Path segmentFolder;

  /**
   * Deletes the leftovers. Call it before the folder's segment store exists, so that the folder
   * holds nothing of a running store.
   *
   * <p>It deletes only in a folder that carries its mark, and it marks only a folder that holds no
   * other entry. A folder that held something else first therefore keeps everything. The mark holds
   * the path of the folder that it marks, so a mark that was copied into another folder marks
   * nothing there. It never throws: what it cannot delete stays.
   */
  public void delete() {
    try {
      Files.createDirectories(segmentFolder);
      if (isMarked()) {
        deleteEachLeftover();
        return;
      }

      if (holdsNoOtherEntry()) {
        writeMark();
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

  // Compared as bytes, so that a mark which is not text reads as the mark of another folder.
  private boolean isMarked() throws IOException {
    return Files.isRegularFile(mark(), LinkOption.NOFOLLOW_LINKS)
        && Arrays.equals(Files.readAllBytes(mark()), markedFolder());
  }

  private byte[] markedFolder() throws IOException {
    return segmentFolder.toRealPath().toString().getBytes(StandardCharsets.UTF_8);
  }

  private Path mark() {
    return segmentFolder.resolve(MARK_NAME);
  }

  // A mark alone claims nothing for another owner: an earlier process may have ended while it
  // wrote the mark.
  private boolean holdsNoOtherEntry() throws IOException {
    try (var others = Files.newDirectoryStream(segmentFolder, entry -> !entry.equals(mark()))) {
      return !others.iterator().hasNext();
    }
  }

  // Replaces whatever holds the mark's place. CREATE_NEW fails on a soft link there, where a plain
  // write would overwrite the file that the link points to.
  private void writeMark() throws IOException {
    Files.deleteIfExists(mark());
    Files.write(mark(), markedFolder(), StandardOpenOption.CREATE_NEW);
  }

  private void deleteEachLeftover() throws IOException {
    for (var entry : listedEntries()) {
      deleteIfLeftover(entry);
    }
  }

  // Read to its end before the first delete: a listing that stays open while its entries are
  // deleted can skip some of them.
  private List<Path> listedEntries() throws IOException {
    var listed = new ArrayList<Path>();
    try (var entries = Files.newDirectoryStream(segmentFolder)) {
      entries.forEach(listed::add);
    }

    return listed;
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

      if (attributes.isRegularFile() && PreparedSegmentFile.isUploadName(name)) {
        Files.delete(entry);
      }
    } catch (IOException e) {
      log.warn("Failed to delete a leftover of the segment folder: {}", entry, e);
    }
  }
}
