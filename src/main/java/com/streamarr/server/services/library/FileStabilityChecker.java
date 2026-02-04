package com.streamarr.server.services.library;

import java.nio.file.Path;

@FunctionalInterface
public interface FileStabilityChecker {

  boolean awaitStability(Path path);
}
