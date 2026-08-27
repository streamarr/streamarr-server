package com.streamarr.server.exceptions;

/**
 * A bounded row-lock wait ran out: another transaction still holds a row this one needs. The caller
 * may retry; nothing about the request itself was wrong.
 */
public class ResourceBusyException extends RuntimeException {

  public ResourceBusyException(Throwable cause) {
    super("Another change is in progress; try again shortly.", cause);
  }
}
