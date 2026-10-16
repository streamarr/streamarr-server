package com.streamarr.server.services.library;

/** How the path given to addLibrary overlaps another library's path. */
public enum LibraryPathOverlap {
  /** The path is inside the other library's path. */
  INSIDE,
  /** The path contains the other library's path. */
  CONTAINS
}
