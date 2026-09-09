package com.streamarr.server.exceptions;

public class AccountRemovalConflictException extends RuntimeException {

  public AccountRemovalConflictException() {
    super("The Account no longer belongs to the source Household.");
  }
}
