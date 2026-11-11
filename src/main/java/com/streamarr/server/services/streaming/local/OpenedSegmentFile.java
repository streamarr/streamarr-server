package com.streamarr.server.services.streaming.local;

import com.streamarr.server.services.streaming.StoredSegment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * A published segment file opened for reading. Every read comes from the opened file, so deleting
 * the file's name afterwards never cuts an answer short.
 */
final class OpenedSegmentFile implements StoredSegment {

  private final FileChannel file;
  private final long length;

  private OpenedSegmentFile(FileChannel file, long length) {
    this.file = file;
    this.length = length;
  }

  static OpenedSegmentFile open(Path path) throws IOException {
    var file = FileChannel.open(path, StandardOpenOption.READ);
    try {
      return new OpenedSegmentFile(file, file.size());
    } catch (IOException | RuntimeException e) {
      file.close();
      throw e;
    }
  }

  @Override
  public long length() {
    return length;
  }

  @Override
  public InputStream newInputStream() {
    return new SegmentInputStream();
  }

  @Override
  public void close() {
    try {
      file.close();
    } catch (IOException _) {
      // The file was opened for reading alone, so a failed close loses nothing.
    }
  }

  private final class SegmentInputStream extends InputStream {

    private long position;

    @Override
    public int read() throws IOException {
      var single = new byte[1];
      if (read(single, 0, 1) <= 0) {
        return -1;
      }

      return single[0] & 0xFF;
    }

    @Override
    public int read(byte[] target, int offset, int count) throws IOException {
      Objects.checkFromIndexSize(offset, count, target.length);
      var wanted = (int) Math.min(count, length - position);
      if (wanted <= 0) {
        return count == 0 ? 0 : -1;
      }

      var read = file.read(ByteBuffer.wrap(target, offset, wanted), position);
      if (read > 0) {
        position += read;
      }

      return read;
    }

    // InputStream.skip reads the bytes it passes over; a byte range near the end of a segment
    // would read the whole file before its first byte.
    @Override
    public long skip(long count) {
      var skipped = Math.clamp(count, 0, Math.max(0, length - position));
      position += skipped;
      return skipped;
    }
  }
}
