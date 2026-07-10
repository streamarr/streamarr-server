package com.streamarr.server.services.auth.invalidation;

class CounterNotificationConnectionException extends RuntimeException {

  CounterNotificationConnectionException(String message, Throwable cause) {
    super(message, cause);
  }
}
