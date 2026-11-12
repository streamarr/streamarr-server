package com.streamarr.server.fakes;

import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StoredSegment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class FakeSegmentStore implements SegmentStore {

  private final Map<UUID, Map<String, byte[]>> sessions = new ConcurrentHashMap<>();
  private final Set<StoredSegment> openSegments = ConcurrentHashMap.newKeySet();

  @Override
  public Optional<StoredSegment> openSegment(UUID sessionId, String segmentName) {
    return Optional.ofNullable(sessions.getOrDefault(sessionId, Map.of()).get(segmentName))
        .map(this::opened);
  }

  /** The segments that were opened and not yet closed. */
  public int openSegmentCount() {
    return openSegments.size();
  }

  // Like a file opened for one request, the segment reads nothing once it is closed.
  private StoredSegment opened(byte[] data) {
    var closed = new AtomicBoolean();
    var opened =
        new StoredSegment() {
          @Override
          public long length() {
            return data.length;
          }

          @Override
          public InputStream newInputStream() {
            return new OpenedSegmentStream(new ByteArrayInputStream(data), closed);
          }

          @Override
          public void close() {
            closed.set(true);
            openSegments.remove(this);
          }
        };
    openSegments.add(opened);
    return opened;
  }

  private static final class OpenedSegmentStream extends FilterInputStream {

    private final AtomicBoolean segmentClosed;

    private OpenedSegmentStream(InputStream bytes, AtomicBoolean segmentClosed) {
      super(bytes);
      this.segmentClosed = segmentClosed;
    }

    @Override
    public int read() throws IOException {
      requireOpenSegment();
      return super.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      requireOpenSegment();
      return super.read(buffer, offset, length);
    }

    @Override
    public long skip(long count) throws IOException {
      requireOpenSegment();
      return super.skip(count);
    }

    private void requireOpenSegment() throws IOException {
      if (segmentClosed.get()) {
        throw new IOException("The segment was closed before its stream was read");
      }
    }
  }

  @Override
  public boolean segmentExists(UUID sessionId, String segmentName) {
    return sessions.getOrDefault(sessionId, Map.of()).containsKey(segmentName);
  }

  @Override
  public PreparedSegment prepareSegment(UUID sessionId, String segmentName) {
    var appended = new ByteArrayOutputStream();
    return new PreparedSegment() {
      @Override
      public void append(ByteBuffer bytes) {
        var chunk = new byte[bytes.remaining()];
        bytes.get(chunk);
        appended.writeBytes(chunk);
      }

      @Override
      public SegmentPublication publish() {
        var data = appended.toByteArray();
        if (SegmentNames.isInitSegment(segmentName)) {
          return publishInitialization(sessionId, segmentName, data);
        }

        addSegmentUnlessStored(sessionId, segmentName, data);
        return SegmentPublication.PUBLISHED;
      }

      @Override
      public void close() {
        // The appended bytes stay on the heap until publish(), so there is nothing to release.
      }
    };
  }

  private SegmentPublication publishInitialization(
      UUID sessionId, String segmentName, byte[] data) {
    var stored =
        sessions
            .computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>())
            .putIfAbsent(segmentName, data);
    if (stored == null || Arrays.equals(stored, data)) {
      return SegmentPublication.PUBLISHED;
    }

    return SegmentPublication.INITIALIZATION_SEGMENT_DIFFERS;
  }

  @Override
  public void deleteSession(UUID sessionId) {
    sessions.remove(sessionId);
  }

  private synchronized void addSegmentUnlessStored(
      UUID sessionId, String segmentName, byte[] data) {
    if (!segmentExists(sessionId, segmentName)) {
      addSegment(sessionId, segmentName, data);
    }
  }

  public void addSegment(UUID sessionId, String segmentName, byte[] data) {
    sessions.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>()).put(segmentName, data);
  }
}
