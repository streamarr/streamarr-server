package com.streamarr.server.exceptions;

public class HouseholdOwnershipTransferRequiredException extends RuntimeException {

  public HouseholdOwnershipTransferRequiredException() {
    super("Transfer household ownership before moving the current owner account.");
  }
}
