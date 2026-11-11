package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.common.primitives.Bytes;
import com.streamarr.server.services.streaming.SegmentPublication;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Prepared Segment File Tests")
class PreparedSegmentFileTest {

  private static final Path DIRECTORY = Path.of("segments");
  private static final Path TARGET = DIRECTORY.resolve("session/720p/segment0.m4s");
  private static final Path INITIALIZATION_TARGET = DIRECTORY.resolve("session/720p/init.mp4");

  // The temporary file is on the default file system and the target on another one. Java moves
  // a file between two file systems by copying it, and refuses to call that move atomic.
  @Test
  @DisplayName(
      "Should fail the publication and store nothing when the volume cannot move a file atomically")
  void shouldFailPublicationAndStoreNothingWhenVolumeCannotMoveFileAtomically(
      @TempDir Path temporaryDirectory) throws Exception {
    try (var otherVolume = Jimfs.newFileSystem(Configuration.unix())) {
      var target = otherVolume.getPath("/segment0.m4s");
      var prepared = PreparedSegmentFile.create(temporaryDirectory);
      prepared.append(ByteBuffer.wrap("complete segment".getBytes()));

      assertThatThrownBy(() -> prepared.publishWhenNothingIsStored(target))
          .isInstanceOf(AtomicMoveNotSupportedException.class);
      prepared.close();

      assertThat(target).doesNotExist();
      assertThat(temporaryDirectory).isEmptyDirectory();
    }
  }

  @Test
  @DisplayName("Should publish the appended bytes when nothing is stored at the target")
  void shouldPublishAppendedBytesWhenNothingIsStoredAtTarget() throws Exception {
    var files = new InMemoryFileOperations();
    var expected = "ftyp moov from encoder A".getBytes();
    var prepared = prepared(files, expected);

    var publication = prepared.publishUnlessStored(INITIALIZATION_TARGET);

    assertThat(publication).isEqualTo(SegmentPublication.PUBLISHED);
    assertThat(files.bytesAt(INITIALIZATION_TARGET)).containsExactly(expected);
    assertThat(files.hasTemporaryFile()).isFalse();
  }

  @Test
  @DisplayName("Should keep the stored bytes when the target already holds different ones")
  void shouldKeepStoredBytesWhenTargetAlreadyHoldsDifferentOnes() throws Exception {
    var files = new InMemoryFileOperations();
    var stored = "ftyp moov from encoder A".getBytes();
    prepared(files, stored).publishUnlessStored(INITIALIZATION_TARGET);
    var differing = prepared(files, "ftyp moov from encoder B".getBytes());

    var publication = differing.publishUnlessStored(INITIALIZATION_TARGET);

    assertThat(publication).isEqualTo(SegmentPublication.INITIALIZATION_SEGMENT_DIFFERS);
    assertThat(files.bytesAt(INITIALIZATION_TARGET)).containsExactly(stored);
  }

  @Test
  @DisplayName("Should suppress cleanup failure when the temporary file cannot be opened")
  void shouldSuppressCleanupFailureWhenTemporaryFileCannotBeOpened() {
    var files = new InMemoryFileOperations();
    files.failOpen = true;
    files.failDelete = true;

    assertThatThrownBy(() -> PreparedSegmentFile.create(files, DIRECTORY))
        .isInstanceOf(IOException.class)
        .hasMessage("temporary open failed")
        .satisfies(
            failure ->
                assertThat(failure.getSuppressed())
                    .singleElement()
                    .satisfies(
                        suppressed -> {
                          assertThat(suppressed).isInstanceOf(IOException.class);
                          assertThat(suppressed.getMessage()).isEqualTo("temporary cleanup failed");
                        }));
  }

  @Test
  @DisplayName("Should delete the temporary file when it cannot be opened for writing")
  void shouldDeleteTemporaryFileWhenItCannotBeOpenedForWriting() {
    var files = new InMemoryFileOperations();
    files.failOpen = true;

    assertThatThrownBy(() -> PreparedSegmentFile.create(files, DIRECTORY))
        .isInstanceOf(IOException.class);

    assertThat(files.hasTemporaryFile()).isFalse();
  }

  @Test
  @DisplayName(
      "Should delete the temporary file when closing its failed channel fails after a failed write")
  void shouldDeleteTemporaryFileWhenClosingItsFailedChannelFailsAfterFailedWrite()
      throws Exception {
    var files = new InMemoryFileOperations();
    var prepared = PreparedSegmentFile.create(files, DIRECTORY);
    files.failWrite = true;
    files.failClose = true;
    assertThatThrownBy(() -> prepared.append(ByteBuffer.wrap(new byte[] {0x47})))
        .isInstanceOf(IOException.class);

    assertThatThrownBy(prepared::close).isInstanceOf(IOException.class);

    assertThat(files.hasTemporaryFile()).isFalse();
  }

  private static PreparedSegmentFile prepared(InMemoryFileOperations files, byte[] data)
      throws IOException {
    var prepared = PreparedSegmentFile.create(files, DIRECTORY);
    prepared.append(ByteBuffer.wrap(data));
    return prepared;
  }

  private static final class InMemoryFileOperations implements PreparedSegmentFile.FileOperations {

    private final Path temporary = DIRECTORY.resolve("upload.tmp");
    private final Map<Path, byte[]> contents = new HashMap<>();
    private boolean failOpen;
    private boolean failWrite;
    private boolean failClose;
    private boolean failDelete;

    @Override
    public Path createTemporary(Path directory) {
      contents.put(temporary, new byte[0]);
      return temporary;
    }

    @Override
    public WritableByteChannel openForWriting(Path path) throws IOException {
      if (failOpen) {
        throw new IOException("temporary open failed");
      }

      return new WritableByteChannel() {
        private boolean open = true;

        @Override
        public int write(ByteBuffer source) throws IOException {
          if (failWrite) {
            throw new IOException("temporary write failed");
          }

          var written = new byte[source.remaining()];
          source.get(written);
          contents.merge(path, written, Bytes::concat);
          return written.length;
        }

        @Override
        public boolean isOpen() {
          return open;
        }

        @Override
        public void close() throws IOException {
          open = false;
          if (failClose) {
            throw new IOException("temporary close failed");
          }
        }
      };
    }

    @Override
    public void moveAtomically(Path source, Path target) {
      contents.put(target, contents.remove(source));
    }

    @Override
    public boolean notExists(Path path) {
      return !contents.containsKey(path);
    }

    @Override
    public boolean hasSameContent(Path first, Path second) {
      return Arrays.equals(contents.get(first), contents.get(second));
    }

    @Override
    public void delete(Path path) throws IOException {
      if (failDelete) {
        throw new IOException("temporary cleanup failed");
      }
      contents.remove(path);
    }

    private byte[] bytesAt(Path path) {
      return contents.get(path);
    }

    private boolean hasTemporaryFile() {
      return contents.containsKey(temporary);
    }
  }
}
