package com.streamarr.server.services.library;

/** How one bitrate scan execution ends (ADR 0054). Its completion decides what follows. */
public sealed interface BitrateScanResult {

  /** The stored outcome no longer needs this scan, or it changed while the chunk ran. */
  record Completed() implements BitrateScanResult {}

  /** The source on disk differs from the scan's snapshot. */
  record SourceChanged() implements BitrateScanResult {}

  /** The execution stored the result of one more chunk. */
  record ChunkStored() implements BitrateScanResult {}

  /** Every keyframe interval already has a stored chunk. */
  record AllChunksStored() implements BitrateScanResult {}

  /** No worker can start the chunk now, so it waits without counting as a failure. */
  record Deferred() implements BitrateScanResult {}

  /** The chunk ran without a result. */
  record Failed() implements BitrateScanResult {}

  /** A stream copy would change the size or the number of a measured stream's packets. */
  record CopyChangesPackets() implements BitrateScanResult {}
}
