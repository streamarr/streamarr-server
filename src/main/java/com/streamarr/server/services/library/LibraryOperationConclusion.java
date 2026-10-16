package com.streamarr.server.services.library;

import com.streamarr.server.domain.LibraryStatus;
import java.util.Locale;

/** How a library operation ends. */
public enum LibraryOperationConclusion {
  FINISHED(LibraryStatus.HEALTHY),
  FAILED(LibraryStatus.UNHEALTHY);

  private final LibraryStatus libraryStatus;

  LibraryOperationConclusion(LibraryStatus libraryStatus) {
    this.libraryStatus = libraryStatus;
  }

  /** The status the library shows once an operation ends this way. */
  public LibraryStatus libraryStatus() {
    return libraryStatus;
  }

  String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
