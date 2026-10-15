package com.streamarr.server.exceptions;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;

/**
 * The image host answered a download with a status other than success, and may have asked for a
 * delay in its {@code Retry-After} header.
 */
public class ImageDownloadException extends IOException {

  @Getter private final int statusCode;
  private final Duration retryAfter;

  public ImageDownloadException(
      int statusCode, @NonNull String path, @NonNull Optional<Duration> retryAfter) {
    super("Image host answered %d for %s".formatted(statusCode, path));
    this.statusCode = statusCode;
    this.retryAfter = retryAfter.orElse(null);
  }

  /** The delay the host asked for before the next request, when it sent one. */
  public Optional<Duration> getRetryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
