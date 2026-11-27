package com.streamarr.server.exceptions;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;

/**
 * The image host answered a download with a status other than success, and may have asked for a
 * delay in its {@code Retry-After} header; or it answered success with a body over the download
 * limit.
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

  private ImageDownloadException(String message) {
    super(message);
    this.statusCode = HttpURLConnection.HTTP_OK;
    this.retryAfter = null;
  }

  /** The host answered success with a body larger than {@code limitBytes}. */
  public static ImageDownloadException bodyOverLimit(@NonNull String path, long limitBytes) {
    return new ImageDownloadException(
        "Image host answered %d for %s with a body over %d bytes"
            .formatted(HttpURLConnection.HTTP_OK, path, limitBytes));
  }

  /** The delay the host asked for before the next request, when it sent one. */
  public Optional<Duration> getRetryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
