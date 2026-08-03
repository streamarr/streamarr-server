package com.streamarr.server.exceptions;

public class InvalidRefreshProposalException extends RuntimeException {

  public InvalidRefreshProposalException() {
    super("The proposed refresh token is not a usable successor.");
  }
}
