package com.streamarr.server.domain;

/** Why a scan skipped a file it found in its library's folder. */
public enum SkippedFileReason {
  /** The file is a soft link. Streamarr never follows a soft link. */
  SOFT_LINK,
  /**
   * The file's extension is not a supported video type, and the ignore list does not ignore the
   * file.
   */
  UNSUPPORTED_FILE_TYPE,
  /** The scan listed the entry but could not read its attributes. */
  UNREADABLE
}
