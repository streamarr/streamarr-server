package com.streamarr.server.exceptions;

import java.io.IOException;
import java.nio.file.Path;
import lombok.NonNull;

/**
 * A folder in a stream session's directory carries no mark of the server's, so the start leaves the
 * folder whole: the server did not create it, or a file system is mounted on it.
 */
public class UnmarkedFolderException extends IOException {

  public UnmarkedFolderException(@NonNull Path folder) {
    super("The folder " + folder + " carries no mark of the server's, so it stays whole");
  }
}
