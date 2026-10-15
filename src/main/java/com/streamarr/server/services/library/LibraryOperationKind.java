package com.streamarr.server.services.library;

import com.streamarr.server.domain.LibraryStatus;
import java.util.Locale;

/** The kind of work a library operation does. */
public enum LibraryOperationKind {
  SCAN(LibraryStatus.SCANNING),
  REFRESH(LibraryStatus.REFRESHING);

  private final LibraryStatus busyStatus;

  LibraryOperationKind(LibraryStatus busyStatus) {
    this.busyStatus = busyStatus;
  }

  /** The status a library shows while an operation of this kind runs. */
  public LibraryStatus busyStatus() {
    return busyStatus;
  }

  /** The value that tags this kind on library-operation metrics. */
  String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
