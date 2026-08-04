package com.streamarr.server.exceptions;

public class LibraryScanFailedException extends RuntimeException {

  public LibraryScanFailedException(String libraryName, Throwable cause) {
    super("Failed to scan library: " + libraryName, cause);
  }

  public LibraryScanFailedException(
      String libraryName, int fileProcessingFailureCount, Throwable cause) {
    super(
        "%d file processing %s failed for library: %s"
            .formatted(
                fileProcessingFailureCount,
                fileProcessingFailureCount == 1 ? "task" : "tasks",
                libraryName),
        cause);
  }
}
