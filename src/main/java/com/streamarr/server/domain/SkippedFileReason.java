package com.streamarr.server.domain;

/** Why a scan skipped a file it found in its library's folder. */
public enum SkippedFileReason {
  /** The file is a soft link. Streamarr never follows a soft link. */
  SOFT_LINK
}
