package com.streamarr.server.services.concurrency;

/** Reads elapsed time in nanoseconds. A step of the system clock does not change the reading. */
@FunctionalInterface
public interface NanoClock {

  long nanoTime();
}
