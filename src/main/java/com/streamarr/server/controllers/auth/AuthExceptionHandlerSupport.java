package com.streamarr.server.controllers.auth;

import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.TooManyAttemptsException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * The handlers every auth advice shares. Not an advice itself: each subclass binds its own
 * controllers through {@code assignableTypes}, and Spring resolves the inherited handlers.
 */
public abstract class AuthExceptionHandlerSupport {

  @ExceptionHandler(TooManyAttemptsException.class)
  public ResponseEntity<AuthErrorResponse> handleTooManyAttempts(TooManyAttemptsException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
        .body(new AuthErrorResponse("TOO_MANY_ATTEMPTS", e.getMessage()));
  }

  @ExceptionHandler(HouseholdRequiredException.class)
  public ResponseEntity<AuthErrorResponse> handleHouseholdRequired(HouseholdRequiredException e) {
    return respond(HttpStatus.BAD_REQUEST, "HOUSEHOLD_REQUIRED", e);
  }

  @ExceptionHandler(HouseholdAccessDeniedException.class)
  public ResponseEntity<AuthErrorResponse> handleHouseholdDenied(HouseholdAccessDeniedException e) {
    return respond(HttpStatus.FORBIDDEN, "HOUSEHOLD_ACCESS_DENIED", e);
  }

  @ExceptionHandler(CredentialAttemptUnavailableException.class)
  public ResponseEntity<AuthErrorResponse> handleCredentialAttemptUnavailable(
      CredentialAttemptUnavailableException e) {
    return respond(HttpStatus.SERVICE_UNAVAILABLE, "CREDENTIAL_VERIFICATION_UNAVAILABLE", e);
  }

  protected static ResponseEntity<AuthErrorResponse> respond(
      HttpStatus status, String code, RuntimeException e) {
    return ResponseEntity.status(status).body(new AuthErrorResponse(code, e.getMessage()));
  }
}
