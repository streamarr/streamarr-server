package com.streamarr.server.exceptions;

public class InvitationEmailAlreadyUsedException extends RuntimeException {

  public InvitationEmailAlreadyUsedException(Throwable cause) {
    super("The invitation email is already in use.", cause);
  }
}
