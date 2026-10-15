package com.streamarr.server.services.audit;

/**
 * Thrown when system work starts on a thread that already has an open transaction. The transaction
 * named its actor when it began, so the system work's writes would name that actor instead of the
 * System actor.
 */
public class SystemWorkInsideTransactionException extends RuntimeException {

  public SystemWorkInsideTransactionException() {
    super(
        "System work must start outside a transaction, so that its transactions name the System actor");
  }
}
