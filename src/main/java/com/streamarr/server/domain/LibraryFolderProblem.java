package com.streamarr.server.domain;

import java.util.Locale;

/** Why a library folder fails the check that a scan or refresh runs before it admits any task. */
public enum LibraryFolderProblem {
  MISSING,
  UNREADABLE,
  EMPTY,
  SOFT_LINK;

  public String metricTag() {
    return name().toLowerCase(Locale.ROOT);
  }
}
