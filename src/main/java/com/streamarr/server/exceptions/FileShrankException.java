package com.streamarr.server.exceptions;

import java.io.IOException;

public class FileShrankException extends IOException {

  public FileShrankException() {
    super("The file ended before the length it had when it was opened");
  }
}
