package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.exceptions.ImageDownloadException;
import com.streamarr.server.exceptions.ImageProcessingException;
import com.streamarr.server.exceptions.ImageStorageException;
import com.streamarr.server.services.ArtworkFailure.Retryable;
import com.streamarr.server.services.ArtworkFailure.Terminal;
import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;

@Tag("UnitTest")
@DisplayName("Artwork failure")
class ArtworkFailureTest {

  @Test
  @DisplayName(
      "Should classify too many requests as retryable with its retry after when the host sends one")
  void shouldClassifyTooManyRequestsAsRetryableWithItsRetryAfterWhenTheHostSendsOne() {
    var failure =
        ArtworkFailure.classify(
            new ImageDownloadException(429, "/poster.jpg", Optional.of(Duration.ofSeconds(90))));

    assertThat(failure).isEqualTo(new Retryable(Optional.of(Duration.ofSeconds(90))));
  }

  @ParameterizedTest
  @ValueSource(ints = {408, 500, 502, 503, 504})
  @DisplayName(
      "Should classify a timeout or server error as retryable when the host answers with it")
  void shouldClassifyATimeoutOrServerErrorAsRetryableWhenTheHostAnswersWithIt(int statusCode) {
    var failure =
        ArtworkFailure.classify(
            new ImageDownloadException(statusCode, "/poster.jpg", Optional.empty()));

    assertThat(failure).isEqualTo(new Retryable(Optional.empty()));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 410})
  @DisplayName("Should classify another client error as a terminal download failure")
  void shouldClassifyAnotherClientErrorAsATerminalDownloadFailure(int statusCode) {
    var failure =
        ArtworkFailure.classify(
            new ImageDownloadException(statusCode, "/poster.jpg", Optional.empty()));

    assertThat(failure).isEqualTo(new Terminal(ItemFailureReason.DOWNLOAD_FAILED));
  }

  @Test
  @DisplayName("Should classify a network error as retryable when the download cannot connect")
  void shouldClassifyANetworkErrorAsRetryableWhenTheDownloadCannotConnect() {
    var failure = ArtworkFailure.classify(new HttpConnectTimeoutException("timed out"));

    assertThat(failure).isEqualTo(new Retryable(Optional.empty()));
  }

  @Test
  @DisplayName("Should classify an undecodable image as terminal invalid media")
  void shouldClassifyAnUndecodableImageAsTerminalInvalidMedia() {
    var failure = ArtworkFailure.classify(new ImageProcessingException("Failed to decode"));

    assertThat(failure).isEqualTo(new Terminal(ItemFailureReason.INVALID_MEDIA));
  }

  @Test
  @DisplayName("Should classify a storage write failure as retryable")
  void shouldClassifyAStorageWriteFailureAsRetryable() {
    var failure = ArtworkFailure.classify(new ImageStorageException(new IOException("disk full")));

    assertThat(failure).isEqualTo(new Retryable(Optional.empty()));
  }

  @Test
  @DisplayName("Should classify a lock timeout as retryable when the database cannot save images")
  void shouldClassifyALockTimeoutAsRetryableWhenTheDatabaseCannotSaveImages() {
    var failure = ArtworkFailure.classify(new CannotAcquireLockException("lock timeout"));

    assertThat(failure).isEqualTo(new Retryable(Optional.empty()));
  }

  @Test
  @DisplayName("Should record a retryable failure as temporary")
  void shouldRecordARetryableFailureAsTemporary() {
    assertThat(new Retryable(Optional.empty()).reason()).isEqualTo(ItemFailureReason.TEMPORARY);
  }

  @Test
  @DisplayName("Should wait for retry after when it exceeds the backoff delay")
  void shouldWaitForRetryAfterWhenItExceedsTheBackoffDelay() {
    var delay = ArtworkFailure.retryDelay(0, Optional.of(Duration.ofSeconds(90)));

    assertThat(delay).isEqualTo(Duration.ofSeconds(90));
  }

  @Test
  @DisplayName("Should wait for the backoff delay when retry after is shorter")
  void shouldWaitForTheBackoffDelayWhenRetryAfterIsShorter() {
    var delay = ArtworkFailure.retryDelay(2, Optional.of(Duration.ofSeconds(1)));

    assertThat(delay).isEqualTo(Duration.ofSeconds(20));
  }

  @Test
  @DisplayName("Should wait for the capped backoff delay when the host sends no retry after")
  void shouldWaitForTheCappedBackoffDelayWhenTheHostSendsNoRetryAfter() {
    var delay = ArtworkFailure.retryDelay(40, Optional.empty());

    assertThat(delay).isEqualTo(Duration.ofSeconds(300));
  }
}
