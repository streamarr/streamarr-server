package com.streamarr.server.services.streaming.local;

import com.streamarr.server.services.streaming.BoundedFileInputStream;
import com.streamarr.server.services.streaming.FileBackedStoredSegment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A published segment file opened for reading. Every read comes from the opened file, so deleting
 * the file's name afterwards never cuts an answer short.
 */
final class OpenedSegmentFile implements FileBackedStoredSegment {

  private final Path path;
  private final FileChannel file;
  private final long length;

  private OpenedSegmentFile(Path path, FileChannel file, long length) {
    this.path = path;
    this.file = file;
    this.length = length;
  }

  static OpenedSegmentFile open(Path path) throws IOException {
    var file = FileChannel.open(path, StandardOpenOption.READ);
    try {
      return new OpenedSegmentFile(path.toAbsolutePath(), file, file.size());
    } catch (IOException | RuntimeException e) {
      file.close();
      throw e;
    }
  }

  @Override
  public Path path() {
    return path;
  }

  @Override
  public long length() {
    return length;
  }

  @Override
  public InputStream newInputStream() {
    return new BoundedFileInputStream(length, file::read);
  }

  @Override
  public void close() {
    try {
      file.close();
    } catch (IOException _) {
      // The file was opened for reading alone, so a failed close loses nothing.
    }
  }
}
