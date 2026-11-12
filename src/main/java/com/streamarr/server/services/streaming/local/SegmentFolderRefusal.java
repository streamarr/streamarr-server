package com.streamarr.server.services.streaming.local;

import java.util.Locale;

/** Why the server deletes no leftovers in a segment folder that only this server uses. */
public enum SegmentFolderRefusal {
  /**
   * The folder is a root directory, such as {@code /}. A folder that a volume is mounted on is not.
   */
  FILE_SYSTEM_ROOT,
  /** The folder is the home folder of the server's user, or a folder above it. */
  HOME_FOLDER,
  /**
   * The folder is the media root or a library folder, or lies above or below one, under any of
   * their paths: a bind mount of a folder in a library lies in that library.
   */
  MEDIA_FOLDER,
  /** The server could not read the library folders, so it cannot tell them from the folder. */
  UNKNOWN_MEDIA_FOLDERS,
  /** A file system is mounted in the folder or below it, so what lies there is not the server's. */
  MOUNT_POINT_INSIDE,
  /**
   * The server could not read its mount table, or the table does not show where the folder or a
   * media folder lies, so it cannot tell whether a file system is mounted in the folder or whether
   * the folder lies in a library under another path. Only Linux has the table.
   */
  UNKNOWN_MOUNT_POINTS,
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
