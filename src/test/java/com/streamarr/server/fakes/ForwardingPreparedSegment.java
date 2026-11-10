package com.streamarr.server.fakes;

import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore.PreparedSegment;
import java.nio.ByteBuffer;

/** Forwards every call to another prepared segment, for a test to override one step of. */
public class ForwardingPreparedSegment implements PreparedSegment {

  private final PreparedSegment delegate;

  public ForwardingPreparedSegment(PreparedSegment delegate) {
    this.delegate = delegate;
  }

  @Override
  public void append(ByteBuffer bytes) {
    delegate.append(bytes);
  }

  @Override
  public SegmentPublication publish() {
    return delegate.publish();
  }

  @Override
  public void close() {
    delegate.close();
  }
}
