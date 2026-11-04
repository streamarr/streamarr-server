package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("File Store Segment Folder Space Tests")
class FileStoreSegmentFolderSpaceTest {

  @TempDir private Path parent;

  @Test
  @DisplayName("Should report the usable share of the folder's file system when the folder exists")
  void shouldReportUsableShareOfFolderFileSystemWhenFolderExists() throws IOException {
    var store = Files.getFileStore(parent);
    var expected = (double) store.getUsableSpace() / store.getTotalSpace();

    var freeFraction = new FileStoreSegmentFolderSpace(parent).freeFraction();

    assertThat(freeFraction).isBetween(0.0, 1.0).isCloseTo(expected, within(0.01));
  }

  @Test
  @DisplayName(
      "Should read the nearest existing parent's file system when the segment folder is not yet"
          + " created")
  void shouldReadNearestExistingParentFileSystemWhenSegmentFolderIsNotYetCreated() {
    var notYetCreated = parent.resolve("streamarr-segments").resolve("nested");

    var freeFraction = new FileStoreSegmentFolderSpace(notYetCreated).freeFraction();

    assertThat(freeFraction)
        .isCloseTo(new FileStoreSegmentFolderSpace(parent).freeFraction(), within(0.01));
    assertThat(notYetCreated).doesNotExist();
  }
}
