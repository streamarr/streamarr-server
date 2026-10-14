package com.streamarr.server.graphql.subscriptions;

/** An operation fell further behind its subscription field than its buffer allows. */
public class SubscriptionOverflowException extends RuntimeException {

  public SubscriptionOverflowException(String field) {
    super("The " + field + " subscription fell behind and was ended; subscribe again.");
  }
}
