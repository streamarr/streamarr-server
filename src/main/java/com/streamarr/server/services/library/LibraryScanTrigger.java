package com.streamarr.server.services.library;

import java.util.UUID;

/** Starts a library's scan on a virtual thread and returns immediately. */
@FunctionalInterface
public interface LibraryScanTrigger {

  void triggerAsyncScan(UUID libraryId);
}
