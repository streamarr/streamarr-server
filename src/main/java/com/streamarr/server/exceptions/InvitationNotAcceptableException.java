package com.streamarr.server.exceptions;

/**
 * A valid invitation whose Household no longer admits the shape it promised (a Profile name taken
 * meanwhile, a manager no longer eligible). The invitation stays PENDING so ServerAdmin can repair
 * the Household and the holder can retry.
 */
public class InvitationNotAcceptableException extends RuntimeException {

  public InvitationNotAcceptableException(String message, Throwable cause) {
    super(message, cause);
  }
}
