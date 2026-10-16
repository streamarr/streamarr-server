package com.streamarr.server.services.library;

import com.streamarr.server.domain.LibraryStatus;
import java.util.Locale;

/** How a library operation ends. */
public enum LibraryOperationConclusion {
  FINISHED,
  FAILED,
  STOPPED;

  /**
   * Returns how an operation with a stored stop ends: a finished operation ends stopped, and a
   * failed operation stays failed.
   */
  public LibraryOperationConclusion afterStop() {
    return switch (this) {
      case FINISHED, STOPPED -> STOPPED;
      case FAILED -> FAILED;
    };
  }

  /**
   * Returns the status the library shows once an operation ends this way. A stopped operation gives
   * back the status the library showed before the operation started.
   */
  public LibraryStatus libraryStatus(LibraryStatus statusBeforeOperation) {
    return switch (this) {
      case FINISHED -> LibraryStatus.HEALTHY;
      case FAILED -> LibraryStatus.UNHEALTHY;
      case STOPPED -> statusBeforeOperation;
    };
  }

  String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
