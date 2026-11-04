package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.SourceContainer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;

/**
 * The opened source file of a direct-play stream session, and its container. Every read comes from
 * the file opened when the request started, so a name on its path that changes afterwards never
 * changes the bytes served. The caller closes it when its answer ends.
 */
public final class DirectPlaySource implements AutoCloseable {

  private final SeekableByteChannel file;
  private final SourceContainer container;
  private final long length;
  private final DirectPlayMetrics metrics;
  private final AtomicBoolean closed = new AtomicBoolean();

  DirectPlaySource(
      @NonNull SeekableByteChannel file,
      @NonNull SourceContainer container,
      @NonNull DirectPlayMetrics metrics)
      throws IOException {
    this.file = file;
    this.container = container;
    this.length = file.size();
    this.metrics = metrics;
    metrics.countOpenedSource();
  }

  public SourceContainer container() {
    return container;
  }

  /** The file's length when it was opened. */
  public long length() {
    return length;
  }

  /**
   * A stream over the opened file from its first byte. Each stream keeps its own position, and
   * closing one leaves the file open for the next.
   */
  public InputStream newInputStream() {
    return new SourceInputStream();
  }

  /** Closes the file. Closing it again does nothing. */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    metrics.countClosedSource();
    try {
      file.close();
    } catch (IOException _) {
      // The file was opened for reading alone, so a failed close loses nothing.
    }
  }

  private synchronized int read(ByteBuffer target, long position) throws IOException {
    file.position(position);
    var read = file.read(target);
    if (read > 0) {
      metrics.countBytesServed(read);
    }

    return read;
  }

  private final class SourceInputStream extends InputStream {

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
      if (count == 0) {
        return 0;
      }

      var read = DirectPlaySource.this.read(ByteBuffer.wrap(target, offset, count), position);
      if (read > 0) {
        position += read;
      }

      return read;
    }

    @Override
    public long skip(long count) {
      var skipped = Math.clamp(count, 0, Math.max(0, length - position));
      position += skipped;
      return skipped;
    }
  }
}
