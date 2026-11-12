package com.streamarr.server.exceptions;

/**
 * A mount table does not show which file system, and which folder in it, a folder's path leads to.
 */
public class UnknownFolderLocationException extends RuntimeException {

  public UnknownFolderLocationException(String reason) {
    super("The mount table does not show where a folder lies: " + reason);
  }
}
