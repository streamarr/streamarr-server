package com.streamarr.server.domain.media;

import lombok.NonNull;

/** How one attempt at an item step ended; {@link Unavailable} means the provider has nothing. */
public sealed interface ItemOutcome {

  record Succeeded() implements ItemOutcome {}

  record Unavailable() implements ItemOutcome {}

  record Failed(@NonNull ItemFailureReason reason, @NonNull String detail) implements ItemOutcome {

    private static final int MAX_DETAIL_LENGTH = 500;

    public Failed {
      if (detail.length() > MAX_DETAIL_LENGTH) {
        detail = detail.substring(0, MAX_DETAIL_LENGTH);
      }
    }

    public static Failed of(@NonNull ItemFailureReason reason, @NonNull Throwable cause) {
      var message = cause.getMessage();
      var detail = cause.getClass().getSimpleName();
      if (message != null && !message.isBlank()) {
        detail += ": " + message;
      }

      return new Failed(reason, detail);
    }
  }
}
