package com.streamarr.server.services.streaming.local;

import com.streamarr.server.services.streaming.SegmentFolderSpace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Reads the free space of the segment folder's file system from the JDK's file store. */
@RequiredArgsConstructor
public class FileStoreSegmentFolderSpace implements SegmentFolderSpace {

  @NonNull private final Path segmentFolder;

  @Override
  public double freeFraction() {
    try {
      var store = Files.getFileStore(nearestExistingFolder());
      return (double) store.getUsableSpace() / store.getTotalSpace();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // The segment store creates the folder with its first segment, inside the nearest existing
  // folder, so that folder's file system is the one the segments will fill.
  private Path nearestExistingFolder() {
    var folder = segmentFolder.toAbsolutePath();
    while (!Files.exists(folder)) {
      folder = folder.getParent();
    }

    return folder;
  }
}
