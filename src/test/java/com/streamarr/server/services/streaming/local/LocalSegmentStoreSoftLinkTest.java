package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Feature;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.exceptions.UnsupportedSegmentFolderException;
import com.streamarr.server.fakes.ThrowingFileSystemWrapper;
import com.streamarr.server.fixtures.StoredSegments;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Local Segment Store Soft Link Tests")
class LocalSegmentStoreSoftLinkTest {

  private static final byte[] MEDIA_FILE = "a media file in a library".getBytes();
  private static final byte[] UPLOAD = "moof mdat of an uploaded media segment".getBytes();
  private static final String UPLOAD_NAME = "\\.upload-\\d+\\.tmp";

  @TempDir Path segmentFolder;
  @TempDir Path outside;

  @Test
  @DisplayName(
      "Should keep a folder outside the segment folder when a deleted stream session's directory"
          + " links to it")
  void shouldKeepAFolderOutsideTheSegmentFolderWhenADeletedStreamSessionsDirectoryLinksToIt()
      throws IOException {
    var store = new LocalSegmentStore(segmentFolder);
    var sessionId = UUID.randomUUID();
    var sessionDirectory = store.getOutputDirectory(sessionId);
    var outsideFile = Files.write(outside.resolve("movie.mkv"), "media".getBytes());
    Files.createSymbolicLink(sessionDirectory.resolve("link"), outside);

    store.deleteSession(sessionId);

    assertThat(outsideFile).exists();
    assertThat(sessionDirectory).doesNotExist();
  }

  // Without secure directory streams, a delete cannot rule out that a soft link replaces a
  // directory while the delete runs. Windows has none.
  @Test
  @DisplayName(
      "Should refuse the segment folder when its file system cannot delete a directory without"
          + " following soft links")
  void
      shouldRefuseTheSegmentFolderWhenItsFileSystemCannotDeleteADirectoryWithoutFollowingSoftLinks()
          throws IOException {
    var withoutSecureDirectoryStreams =
        Configuration.unix().toBuilder()
            .setSupportedFeatures(Feature.LINKS, Feature.SYMBOLIC_LINKS, Feature.FILE_CHANNEL)
            .build();
    try (var fileSystem = Jimfs.newFileSystem(withoutSecureDirectoryStreams)) {
      var folder = fileSystem.getPath("/segments");

      assertThatThrownBy(() -> new LocalSegmentStore(folder))
          .isInstanceOf(UnsupportedSegmentFolderException.class)
          .hasMessageContaining("/segments");
    }
  }

  @Test
  @DisplayName("Should fail when the segment folder cannot be created")
  void shouldFailWhenTheSegmentFolderCannotBeCreated() throws IOException {
    var regularFile = Files.createFile(outside.resolve("not-a-folder"));
    var folder = regularFile.resolve("segments");

    assertThatThrownBy(() -> new LocalSegmentStore(folder))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining(folder.toString());
  }

  @Test
  @DisplayName(
      "Should write no uploaded byte to a media file when a soft link to it replaces each file in"
          + " the segment folder before the file opens again")
  void shouldWriteNoUploadedByteToAMediaFileWhenASoftLinkReplacesEachFileBeforeItOpensAgain()
      throws IOException {
    try (var volume = Jimfs.newFileSystem(Configuration.unix())) {
      var mediaFile = mediaFileIn(volume);
      var segments = volume.getPath("/segments");
      var linkingVolume =
          ThrowingFileSystemWrapper.runningBeforeEachFileOpens(
              volume, replacingEachFileDirectlyInsideWithASoftLink(segments, mediaFile));
      var store = new LocalSegmentStore(linkingVolume.getPath("/segments"));
      var sessionId = UUID.randomUUID();

      store.storeSegment(sessionId, "segment0.m4s", UPLOAD);

      assertThat(mediaFile)
          .as("the media file that the soft link points to")
          .hasBinaryContent(MEDIA_FILE);
      assertThat(StoredSegments.bytes(store, sessionId, "segment0.m4s")).isEqualTo(UPLOAD);
      assertThat(entryNames(segments))
          .as("no temporary upload file remains in the segment folder")
          .containsExactly(sessionId.toString());
    }
  }

  @Test
  @DisplayName(
      "Should store the upload under another upload name when a soft link to a media file holds"
          + " the first name")
  void shouldStoreTheUploadUnderAnotherUploadNameWhenASoftLinkToAMediaFileHoldsTheFirstName()
      throws IOException {
    try (var volume = Jimfs.newFileSystem(Configuration.unix())) {
      var mediaFile = mediaFileIn(volume);
      var segments = volume.getPath("/segments");
      var linkingVolume =
          ThrowingFileSystemWrapper.runningBeforeEachFileOpens(
              volume, placingSoftLinksAtFreeNamesDirectlyInside(segments, mediaFile, 1));
      var store = new LocalSegmentStore(linkingVolume.getPath("/segments"));
      var sessionId = UUID.randomUUID();

      store.storeSegment(sessionId, "segment0.m4s", UPLOAD);

      assertThat(mediaFile)
          .as("the media file that the soft link points to")
          .hasBinaryContent(MEDIA_FILE);
      assertThat(StoredSegments.bytes(store, sessionId, "segment0.m4s")).isEqualTo(UPLOAD);
      assertThat(entryNames(segments))
          .as("the stream session's folder and the soft link that held the first upload name")
          .hasSize(2)
          .contains(sessionId.toString())
          .anySatisfy(name -> assertThat(name).matches(UPLOAD_NAME));
    }
  }

  // Each upload name that the store tries is held already, so only a bounded number of tries
  // ends the preparation.
  @Test
  @Timeout(10)
  @DisplayName(
      "Should fail the preparation and write no byte to a media file when soft links to it hold"
          + " every upload name that the store tries")
  void shouldFailThePreparationWhenSoftLinksToAMediaFileHoldEveryUploadNameThatTheStoreTries()
      throws IOException {
    try (var volume = Jimfs.newFileSystem(Configuration.unix())) {
      var mediaFile = mediaFileIn(volume);
      var linkingVolume =
          ThrowingFileSystemWrapper.runningBeforeEachFileOpens(
              volume,
              placingSoftLinksAtFreeNamesDirectlyInside(
                  volume.getPath("/segments"), mediaFile, Integer.MAX_VALUE));
      var store = new LocalSegmentStore(linkingVolume.getPath("/segments"));

      assertThatThrownBy(() -> store.prepareSegment(UUID.randomUUID(), "segment0.m4s"))
          .isInstanceOf(UncheckedIOException.class);

      assertThat(mediaFile)
          .as("the media file that the soft links point to")
          .hasBinaryContent(MEDIA_FILE);
    }
  }

  // Stands in for a soft link that takes a file's name in the segment folder once the file exists.
  private static Consumer<Path> replacingEachFileDirectlyInsideWithASoftLink(
      Path folder, Path linkTarget) {
    return opened -> {
      if (folder.equals(opened.getParent())
          && Files.isRegularFile(opened, LinkOption.NOFOLLOW_LINKS)) {
        replaceWithSoftLink(opened, linkTarget);
      }
    };
  }

  // Stands in for other entries that already hold the first names that the store tries in the
  // segment folder.
  private static Consumer<Path> placingSoftLinksAtFreeNamesDirectlyInside(
      Path folder, Path linkTarget, int names) {
    var remaining = new AtomicInteger(names);
    return opened -> {
      if (folder.equals(opened.getParent())
          && Files.notExists(opened, LinkOption.NOFOLLOW_LINKS)
          && remaining.getAndDecrement() > 0) {
        createSoftLink(opened, linkTarget);
      }
    };
  }

  private static void replaceWithSoftLink(Path file, Path linkTarget) {
    try {
      Files.delete(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    createSoftLink(file, linkTarget);
  }

  private static void createSoftLink(Path link, Path linkTarget) {
    try {
      Files.createSymbolicLink(link, linkTarget);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path mediaFileIn(FileSystem volume) throws IOException {
    var library = Files.createDirectory(volume.getPath("/library"));
    return Files.write(library.resolve("movie.mkv"), MEDIA_FILE);
  }

  private static List<String> entryNames(Path folder) throws IOException {
    try (var entries = Files.list(folder)) {
      return entries.map(entry -> entry.getFileName().toString()).toList();
    }
  }
}
