package com.streamarr.server.services.streaming.local;

import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.transcode.engine.segment.LocalSegmentStorage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

public class LocalSegmentStore implements SegmentStore {

  private final LocalSegmentStorage storage;

  public LocalSegmentStore(Path baseDir) {
    this(new LocalSegmentStorage(baseDir));
  }

  public LocalSegmentStore(LocalSegmentStorage storage) {
    this.storage = storage;
  }

  @Override
  public byte[] readSegment(UUID sessionId, String segmentName) {
    try {
      return storage.readSegment(sessionId, segmentName);
    } catch (com.streamarr.transcode.engine.segment.InvalidSegmentPathException _) {
      throw new InvalidSegmentPathException(segmentName);
    }
  }

  @Override
  public boolean waitForSegment(UUID sessionId, String segmentName, Duration timeout) {
    try {
      return storage.waitForSegment(sessionId, segmentName, timeout);
    } catch (com.streamarr.transcode.engine.segment.InvalidSegmentPathException _) {
      throw new InvalidSegmentPathException(segmentName);
    }
  }

  @Override
  public boolean segmentExists(UUID sessionId, String segmentName) {
    try {
      return storage.segmentExists(sessionId, segmentName);
    } catch (com.streamarr.transcode.engine.segment.InvalidSegmentPathException _) {
      throw new InvalidSegmentPathException(segmentName);
    }
  }

  public Path getOutputDirectory(UUID sessionId) {
    return storage.getOutputDirectory(sessionId);
  }

  public Path getOutputDirectory(UUID sessionId, String variantLabel) {
    return storage.getOutputDirectory(sessionId, variantLabel);
  }

  @Override
  public Set<UUID> snapshotStoredSessionIds() {
    return storage.snapshotStoredSessionIds();
  }

  @Override
  public void deleteSession(UUID sessionId) {
    storage.deleteSession(sessionId);
  }

  public void shutdown() {
    storage.shutdown();
  }
}
