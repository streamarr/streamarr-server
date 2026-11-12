package com.streamarr.server.services.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * An independent cursor over the file's length at open. Closing the stream leaves the file with its
 * owner.
 */
@RequiredArgsConstructor
public final class BoundedFileInputStream extends InputStream {

  @FunctionalInterface
  public interface ReadAt {
    int read(ByteBuffer target, long position) throws IOException;
  }

  private final long length;
  @NonNull private final ReadAt reader;
  private long position;

  @Override
  public int read() throws IOException {
    var single = new byte[1];
    return read(single, 0, 1) > 0 ? Byte.toUnsignedInt(single[0]) : -1;
  }

  @Override
  public int read(byte[] target, int offset, int count) throws IOException {
    Objects.checkFromIndexSize(offset, count, target.length);
    if (count == 0) {
      return 0;
    }

    if (position == length) {
      return -1;
    }

    var wanted = (int) Math.min(count, length - position);
    var read = reader.read(ByteBuffer.wrap(target, offset, wanted), position);
    if (read > 0) {
      position += read;
    }

    return read;
  }

  @Override
  public long skip(long count) {
    var skipped = Math.clamp(count, 0L, length - position);
    position += skipped;
    return skipped;
  }
}
