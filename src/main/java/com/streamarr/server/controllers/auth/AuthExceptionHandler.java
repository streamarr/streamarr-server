package com.streamarr.server.controllers.auth;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.exceptions.InvalidProfilePinException;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.exceptions.InvitationEmailAlreadyUsedException;
import com.streamarr.server.exceptions.InvitationNotAcceptableException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileLockedException;
import com.streamarr.server.exceptions.ResourceBusyException;
import com.streamarr.server.exceptions.SetupAlreadyCompletedException;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
    assignableTypes = {
      AuthController.class,
      InvitationController.class,
      PasswordResetController.class
    })
@Slf4j
public class AuthExceptionHandler {

  // Do not reveal whether a rejected refresh token was ever valid.
  private static final String REFRESH_TOKEN_REJECTED = "The refresh token is unknown or expired.";
  private static final String REQUEST_NOT_COMPLETED = "The request could not be completed.";
  private static final String RESOURCE_BUSY = "Another change is in progress; try again shortly.";

  @ExceptionHandler(SetupAlreadyCompletedException.class)
  public ResponseEntity<AuthErrorResponse> handleSetupAlreadyCompleted(
      SetupAlreadyCompletedException e) {
    return respond(HttpStatus.CONFLICT, "SETUP_ALREADY_COMPLETED", e);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<AuthErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
    return respond(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", e);
  }

  @ExceptionHandler({TooManyLoginAttemptsException.class, TooManyCredentialAttemptsException.class})
  public ResponseEntity<AuthErrorResponse> handleTooManyAttempts(RuntimeException e) {
    return respond(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", e);
  }

  @ExceptionHandler({InvalidRefreshTokenException.class, TokenReuseDetectedException.class})
  public ResponseEntity<AuthErrorResponse> handleInvalidRefresh() {
    return respond(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", REFRESH_TOKEN_REJECTED);
  }

  @ExceptionHandler(InvalidOneTimeCodeException.class)
  public ResponseEntity<AuthErrorResponse> handleInvalidOneTimeCode(InvalidOneTimeCodeException e) {
    return respond(HttpStatus.NOT_FOUND, "INVALID_CODE", e);
  }

  @ExceptionHandler(InvitationEmailAlreadyUsedException.class)
  public ResponseEntity<AuthErrorResponse> handleInvitationEmailAlreadyUsed(
      InvitationEmailAlreadyUsedException e) {
    return respond(HttpStatus.CONFLICT, "INVITATION_EMAIL_ALREADY_USED", e);
  }

  @ExceptionHandler(InvitationNotAcceptableException.class)
  public ResponseEntity<AuthErrorResponse> handleInvitationNotAcceptable(
      InvitationNotAcceptableException e) {
    return respond(HttpStatus.CONFLICT, "INVITATION_NOT_ACCEPTABLE", e);
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

  @ExceptionHandler(InvalidProfilePinException.class)
  public ResponseEntity<AuthErrorResponse> handleInvalidProfilePin(InvalidProfilePinException e) {
    return respond(HttpStatus.UNAUTHORIZED, "INVALID_PROFILE_PIN", e);
  }

  @ExceptionHandler(ProfileLockedException.class)
  public ResponseEntity<AuthErrorResponse> handleProfileLocked(ProfileLockedException e) {
    return respond(HttpStatus.CONFLICT, "PROFILE_LOCKED", e);
  }

  @ExceptionHandler(AuthorizationUnavailableException.class)
  public ResponseEntity<AuthErrorResponse> handleAuthorizationUnavailable(
      AuthorizationUnavailableException e) {
    return respond(HttpStatus.SERVICE_UNAVAILABLE, "AUTHORIZATION_UNAVAILABLE", e);
  }

  /**
   * A bounded row-lock wait that ran out is contention, not a defect: retry, and no stack trace.
   */
  @ExceptionHandler({PessimisticLockingFailureException.class, ResourceBusyException.class})
  public ResponseEntity<AuthErrorResponse> handleLockContention(RuntimeException e) {
    log.warn("Auth request gave up waiting for a row lock: {}", e.getMessage());
    return respond(HttpStatus.SERVICE_UNAVAILABLE, "RESOURCE_BUSY", RESOURCE_BUSY);
  }

  /** Persistence failures never masquerade as a wrong code or a bodyless default page. */
  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<AuthErrorResponse> handlePersistenceFailure(DataAccessException e) {
    log.error("Auth persistence failure", e);
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", REQUEST_NOT_COMPLETED);
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
