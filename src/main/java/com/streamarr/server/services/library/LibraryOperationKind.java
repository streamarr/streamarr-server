package com.streamarr.server.services.library;

import java.util.Locale;

/** The kind of work a library operation does. */
public enum LibraryOperationKind {
  SCAN,
  REFRESH;

  /** The value that tags this kind on library-operation metrics. */
  String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
