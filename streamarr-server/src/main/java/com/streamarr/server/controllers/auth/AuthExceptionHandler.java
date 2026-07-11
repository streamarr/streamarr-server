package com.streamarr.server.controllers.auth;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.SetupAlreadyCompletedException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

  // Do not reveal whether a rejected refresh token was ever valid.
  private static final String REFRESH_TOKEN_REJECTED = "The refresh token is unknown or expired.";

  @ExceptionHandler(SetupAlreadyCompletedException.class)
  public ResponseEntity<AuthErrorResponse> handleSetupAlreadyCompleted(
      SetupAlreadyCompletedException e) {
    return respond(HttpStatus.CONFLICT, "SETUP_ALREADY_COMPLETED", e);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<AuthErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
    return respond(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", e);
  }

  @ExceptionHandler(TooManyLoginAttemptsException.class)
  public ResponseEntity<AuthErrorResponse> handleTooManyAttempts(TooManyLoginAttemptsException e) {
    return respond(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", e);
  }

  @ExceptionHandler({InvalidRefreshTokenException.class, TokenReuseDetectedException.class})
  public ResponseEntity<AuthErrorResponse> handleInvalidRefresh() {
    return respond(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", REFRESH_TOKEN_REJECTED);
  }

  @ExceptionHandler(AuthenticationRequiredException.class)
  public ResponseEntity<AuthErrorResponse> handleAuthenticationRequired(
      AuthenticationRequiredException e) {
    return respond(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", e);
  }

  @ExceptionHandler(HouseholdRequiredException.class)
  public ResponseEntity<AuthErrorResponse> handleHouseholdRequired(HouseholdRequiredException e) {
    return respond(HttpStatus.BAD_REQUEST, "HOUSEHOLD_REQUIRED", e);
  }

  @ExceptionHandler(HouseholdAccessDeniedException.class)
  public ResponseEntity<AuthErrorResponse> handleHouseholdDenied(HouseholdAccessDeniedException e) {
    return respond(HttpStatus.FORBIDDEN, "HOUSEHOLD_ACCESS_DENIED", e);
  }

  @ExceptionHandler(ProfileAccessDeniedException.class)
  public ResponseEntity<AuthErrorResponse> handleProfileDenied(ProfileAccessDeniedException e) {
    return respond(HttpStatus.FORBIDDEN, "PROFILE_ACCESS_DENIED", e);
  }

  private static ResponseEntity<AuthErrorResponse> respond(
      HttpStatus status, String code, RuntimeException e) {
    return respond(status, code, e.getMessage());
  }

  private static ResponseEntity<AuthErrorResponse> respond(
      HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(new AuthErrorResponse(code, message));
  }
}
