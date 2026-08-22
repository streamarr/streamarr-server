package com.streamarr.server.services.authorization.cedar;

final class InvalidEntitySliceException extends RuntimeException {

  InvalidEntitySliceException(String message) {
    super(message);
  }
}
