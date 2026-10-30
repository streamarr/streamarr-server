package com.streamarr.server.exceptions;

/**
 * Another transaction holds, or has just changed, a row this one needs. The caller may retry;
 * nothing about the request itself was wrong.
 */
public class ResourceBusyException extends RuntimeException {

  public ResourceBusyException(Throwable cause) {
    super("Another change is in progress; try again shortly.", cause);
  }
}
