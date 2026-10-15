package com.streamarr.server.services;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.exceptions.ImageDownloadException;
import com.streamarr.server.exceptions.ImageProcessingException;
import com.streamarr.server.services.concurrency.CappedExponentialBackoff;
import java.time.Duration;
import java.util.Optional;
import lombok.NonNull;

/**
 * How an artwork step treats an image it could not save. A retryable failure is recorded as {@link
 * ItemFailureReason#TEMPORARY} and tried again later; a terminal failure is final for the request.
 */
public sealed interface ArtworkFailure {

  ItemFailureReason reason();

  /** A failure that may pass: a network error, a timeout, a server error or a storage error. */
  record Retryable(@NonNull Optional<Duration> retryAfter) implements ArtworkFailure {

    @Override
    public ItemFailureReason reason() {
      return ItemFailureReason.TEMPORARY;
    }
  }

  /** A failure that the same request would meet again, such as a missing or undecodable image. */
  record Terminal(@NonNull ItemFailureReason reason) implements ArtworkFailure {}

  /**
   * Network errors, {@code 408}, {@code 429} and {@code 5xx} answers, storage and database errors
   * are retryable. Other {@code 4xx} answers and an undecodable image are terminal.
   */
  static ArtworkFailure classify(@NonNull Throwable cause) {
    return switch (cause) {
      case ImageDownloadException download -> classifyAnswer(download);
      case ImageProcessingException _ -> new Terminal(ItemFailureReason.INVALID_MEDIA);
      default -> new Retryable(Optional.empty());
    };
  }

  /**
   * Returns how long a retry waits: the longer of the host's {@code Retry-After} and the capped
   * exponential backoff after {@code previousFailures} failed attempts.
   */
  static Duration retryDelay(int previousFailures, @NonNull Optional<Duration> retryAfter) {
    var backoff = CappedExponentialBackoff.delayAfter(previousFailures);
    return retryAfter.filter(requested -> requested.compareTo(backoff) > 0).orElse(backoff);
  }

  private static ArtworkFailure classifyAnswer(ImageDownloadException download) {
    if (isRetryableStatus(download.getStatusCode())) {
      return new Retryable(download.getRetryAfter());
    }

    return new Terminal(ItemFailureReason.DOWNLOAD_FAILED);
  }

  private static boolean isRetryableStatus(int statusCode) {
    if (statusCode >= 500) {
      return true;
    }

    return statusCode == 408 || statusCode == 429;
  }
}
