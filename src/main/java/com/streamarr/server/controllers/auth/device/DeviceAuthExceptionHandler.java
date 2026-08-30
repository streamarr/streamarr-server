package com.streamarr.server.controllers.auth.device;

import com.streamarr.server.controllers.auth.AuthErrorResponse;
import com.streamarr.server.exceptions.DeviceCodeExpiredException;
import com.streamarr.server.exceptions.DeviceCodeNotFoundException;
import com.streamarr.server.exceptions.DeviceCodeNotPendingException;
import com.streamarr.server.exceptions.DevicePairingNotConfiguredException;
import com.streamarr.server.exceptions.EsnBlockedException;
import com.streamarr.server.exceptions.EsnRequiredException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.InvalidDecisionException;
import com.streamarr.server.exceptions.InvalidEsnException;
import com.streamarr.server.exceptions.InvalidUserCodeException;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to the device controller: poll states are results, not exceptions, so only the
 * lookup/decision/issuance failures land here. The existing AuthExceptionHandler is bound to
 * AuthController and does not cover this surface.
 */
@RestControllerAdvice(assignableTypes = DeviceAuthController.class)
public class DeviceAuthExceptionHandler {

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<AuthErrorResponse> handleUnreadableRequestBody() {
    return ResponseEntity.badRequest()
        .body(
            new AuthErrorResponse("INVALID_REQUEST", "The request body is missing or malformed."));
  }

  @ExceptionHandler(EsnRequiredException.class)
  public ResponseEntity<AuthErrorResponse> handleEsnRequired(EsnRequiredException e) {
    return respond(HttpStatus.BAD_REQUEST, "ESN_REQUIRED", e);
  }

  @ExceptionHandler(InvalidEsnException.class)
  public ResponseEntity<AuthErrorResponse> handleInvalidEsn(InvalidEsnException e) {
    return respond(HttpStatus.BAD_REQUEST, "INVALID_ESN", e);
  }

  @ExceptionHandler(HouseholdRequiredException.class)
  public ResponseEntity<AuthErrorResponse> handleHouseholdRequired(HouseholdRequiredException e) {
    return respond(HttpStatus.BAD_REQUEST, "HOUSEHOLD_REQUIRED", e);
  }

  @ExceptionHandler(HouseholdAccessDeniedException.class)
  public ResponseEntity<AuthErrorResponse> handleHouseholdDenied(HouseholdAccessDeniedException e) {
    return respond(HttpStatus.FORBIDDEN, "HOUSEHOLD_ACCESS_DENIED", e);
  }

  @ExceptionHandler(EsnBlockedException.class)
  public ResponseEntity<AuthErrorResponse> handleEsnBlocked(EsnBlockedException e) {
    return respond(HttpStatus.FORBIDDEN, "ESN_BLOCKED", e);
  }

  @ExceptionHandler(DevicePairingNotConfiguredException.class)
  public ResponseEntity<AuthErrorResponse> handleNotConfigured(
      DevicePairingNotConfiguredException e) {
    return respond(HttpStatus.SERVICE_UNAVAILABLE, "DEVICE_PAIRING_NOT_CONFIGURED", e);
  }

  @ExceptionHandler(InvalidUserCodeException.class)
  public ResponseEntity<AuthErrorResponse> handleInvalidUserCode(InvalidUserCodeException e) {
    return respond(HttpStatus.BAD_REQUEST, "INVALID_USER_CODE", e);
  }

  @ExceptionHandler(InvalidDecisionException.class)
  public ResponseEntity<AuthErrorResponse> handleInvalidDecision(InvalidDecisionException e) {
    return respond(HttpStatus.BAD_REQUEST, "INVALID_DECISION", e);
  }

  @ExceptionHandler(DeviceCodeNotFoundException.class)
  public ResponseEntity<AuthErrorResponse> handleNotFound(DeviceCodeNotFoundException e) {
    return respond(HttpStatus.NOT_FOUND, "DEVICE_CODE_NOT_FOUND", e);
  }

  @ExceptionHandler(DeviceCodeExpiredException.class)
  public ResponseEntity<AuthErrorResponse> handleExpired(DeviceCodeExpiredException e) {
    return respond(HttpStatus.BAD_REQUEST, "DEVICE_CODE_EXPIRED", e);
  }

  @ExceptionHandler(DeviceCodeNotPendingException.class)
  public ResponseEntity<AuthErrorResponse> handleNotPending(DeviceCodeNotPendingException e) {
    return respond(HttpStatus.CONFLICT, "DEVICE_CODE_NOT_PENDING", e);
  }

  @ExceptionHandler(TooManyDeviceAttemptsException.class)
  public ResponseEntity<AuthErrorResponse> handleTooManyAttempts(TooManyDeviceAttemptsException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(e.getRetryAfter())))
        .body(new AuthErrorResponse("TOO_MANY_ATTEMPTS", e.getMessage()));
  }

  /** Delta-seconds, rounded up: a client that retries a fraction early would just be refused. */
  private static long retryAfterSeconds(Duration retryAfter) {
    if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
      return 1;
    }

    return retryAfter.plusNanos(999_999_999L).toSeconds();
  }

  private static ResponseEntity<AuthErrorResponse> respond(
      HttpStatus status, String code, RuntimeException e) {
    return ResponseEntity.status(status).body(new AuthErrorResponse(code, e.getMessage()));
  }
}
