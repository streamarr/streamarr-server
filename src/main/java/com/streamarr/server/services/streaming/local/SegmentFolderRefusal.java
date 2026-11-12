package com.streamarr.server.services.streaming.local;

import java.util.Locale;

/** Why the server deletes no leftovers in a segment folder that only this server uses. */
public enum SegmentFolderRefusal {
  /** The folder is the top of a file system. */
  FILE_SYSTEM_ROOT,
  /** The folder is the home folder of the server's user, or a folder above it. */
  HOME_FOLDER,
  /** The folder is the media root or a library folder, or lies above or below one. */
  MEDIA_FOLDER,
  /** The server could not read the library folders, so it cannot tell them from the folder. */
  UNKNOWN_MEDIA_FOLDERS,
  /** The folder held other entries before a segment store used it, so it carries no mark. */
  UNMARKED_FOLDER,
  /** Another server process that is running uses the folder. */
  IN_USE,
  /** The folder could not be created, read, marked or locked. */
  UNREADABLE_FOLDER;

  public String metricTag() {
    return name().toLowerCase(Locale.ROOT);
  }
}
