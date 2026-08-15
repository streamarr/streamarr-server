package com.streamarr.server.exceptions;

public class HouseholdOwnershipTransferRequiredException extends RuntimeException {

  /**
   * Creates an exception indicating that household ownership must be transferred
   * before moving the current owner account.
   */
  public HouseholdOwnershipTransferRequiredException() {
    super("Transfer household ownership before moving the current owner account.");
  }
}
