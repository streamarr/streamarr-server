package com.streamarr.server.exceptions;

import java.nio.file.Path;

/** The segment folder's file system cannot delete a directory without following soft links. */
public class UnsupportedSegmentFolderException extends RuntimeException {

  public UnsupportedSegmentFolderException(Path segmentFolder) {
    super(
        "The file system of segment folder "
            + segmentFolder
            + " cannot delete a directory without following soft links."
            + " Run the server on Linux or macOS, or in its container image.");
  }
}
