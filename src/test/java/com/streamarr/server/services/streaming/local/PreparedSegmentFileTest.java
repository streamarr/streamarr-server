package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Prepared Segment File Tests")
class PreparedSegmentFileTest {

  private static final Path DIRECTORY = Path.of("segments");
  private static final Path TARGET = DIRECTORY.resolve("session/720p/segment0.ts");

  @Test
  @DisplayName("Should fall back to a replacement move when atomic move is unavailable")
  void shouldFallBackToReplacementMoveWhenAtomicMoveIsUnavailable() throws Exception {
    var files = new InMemoryFileOperations();
    var expected = "complete segment".getBytes();
    var prepared = PreparedSegmentFile.create(files, DIRECTORY, expected);

    prepared.publishTo(TARGET);

    assertThat(files.bytesAt(TARGET)).containsExactly(expected);
    assertThat(files.hasTemporaryFile()).isFalse();
  }

  @Test
  @DisplayName("Should suppress cleanup failure when the temporary write fails")
  void shouldSuppressCleanupFailureWhenTemporaryWriteFails() {
    var files = new InMemoryFileOperations();
    files.failWrite = true;
    files.failDelete = true;

    assertThatThrownBy(() -> PreparedSegmentFile.create(files, DIRECTORY, new byte[] {0x47}))
        .isInstanceOf(IOException.class)
        .hasMessage("temporary write failed")
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

  private static final class InMemoryFileOperations implements PreparedSegmentFile.FileOperations {

    private final Path temporary = DIRECTORY.resolve("upload.tmp");
    private final Map<Path, byte[]> contents = new HashMap<>();
    private boolean failWrite;
    private boolean failDelete;

    @Override
    public Path createTemporary(Path directory) {
      contents.put(temporary, new byte[0]);
      return temporary;
    }

    @Override
    public void write(Path path, byte[] data) throws IOException {
      if (failWrite) {
        throw new IOException("temporary write failed");
      }
      contents.put(path, data.clone());
    }

    @Override
    public void moveAtomically(Path source, Path target) throws IOException {
      throw new AtomicMoveNotSupportedException(
          source.toString(), target.toString(), "atomic moves disabled");
    }

    @Override
    public void moveReplacing(Path source, Path target) {
      contents.put(target, contents.remove(source));
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
