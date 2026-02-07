package com.streamarr.server.services.library;

import java.util.UUID;

@FunctionalInterface
public interface ActiveScanChecker {

  boolean isActivelyScanning(UUID libraryId);
}
