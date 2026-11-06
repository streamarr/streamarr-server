package com.streamarr.server.fixtures;

import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.SeekPriority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public final class SeekPriorityFixture {

  private SeekPriorityFixture() {}

  /** Seek priority whose held segment requests no coordinator shares, so none is ever held. */
  public static SeekPriority.SeekPriorityBuilder seekPriorityBuilder() {
    return SeekPriority.builder().heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()));
  }
}
