package com.streamarr.server.graphql;

import com.netflix.graphql.dgs.exceptions.DefaultDataFetcherExceptionHandler;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdOwnershipTransferRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.InvalidProfilePinException;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileDeletionBlockedException;
import com.streamarr.server.exceptions.ProfileManagementDeniedException;
import com.streamarr.server.exceptions.ProfileManagerInvariantException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.exceptions.ProfileSafetyViolationException;
import com.streamarr.server.exceptions.ServerAdministrationDeniedException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Maps identity/authorization failures and routine session misses to machine codes in
 * extensions.code — the GraphQL side of the client contract (PROFILE_REQUIRED routes to the picker,
 * SESSION_NOT_FOUND to session recreation — not an error page). Everything else falls through to
 * the DGS default.
 */
@Component
public class StreamarrDataFetcherExceptionHandler implements DataFetcherExceptionHandler {

  private final DataFetcherExceptionHandler delegate = new DefaultDataFetcherExceptionHandler();

  @Override
  public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
      DataFetcherExceptionHandlerParameters handlerParameters) {
    var exception = unwrap(handlerParameters.getException());
    var extensions = extensionsFor(exception);

    if (extensions.isEmpty()) {
      return delegate.handleException(handlerParameters);
    }

    var error =
        GraphqlErrorBuilder.newError(handlerParameters.getDataFetchingEnvironment())
            .message(messageFor(exception))
            .extensions(extensions)
            .build();

    return CompletableFuture.completedFuture(
        DataFetcherExceptionHandlerResult.newResult().error(error).build());
  }

  private static Throwable unwrap(Throwable exception) {
    if (exception instanceof CompletionException completion && completion.getCause() != null) {
      return completion.getCause();
    }
    return exception;
  }

  private static Map<String, Object> extensionsFor(Throwable exception) {
    var code = codeFor(exception);
    if (code == null) {
      return Map.of();
    }
    if (exception instanceof ProfileSafetyViolationException violation) {
      return Map.of(
          "code",
          code,
          "profileIds",
          violation.profilesRequiringPin().stream().map(Object::toString).toList());
    }
    return Map.of("code", code);
  }

  private static String codeFor(Throwable exception) {
    return switch (exception) {
      case ProfileRequiredException _ -> "PROFILE_REQUIRED";
      case AuthenticationRequiredException _ -> "AUTHENTICATION_REQUIRED";
      case AccessDeniedException _ -> "FORBIDDEN";
      case SessionNotFoundException _ -> "SESSION_NOT_FOUND";
      case InvalidCredentialsException _ -> "INVALID_CREDENTIALS";
      case TooManyCredentialAttemptsException _ -> "TOO_MANY_CREDENTIAL_ATTEMPTS";
      case InvalidProfilePinException _ -> "INVALID_PROFILE_PIN";
      case HouseholdAccessDeniedException _ -> "HOUSEHOLD_ACCESS_DENIED";
      case HouseholdOwnershipTransferRequiredException _ -> "HOUSEHOLD_OWNERSHIP_TRANSFER_REQUIRED";
      case ProfileAccessDeniedException _ -> "PROFILE_ACCESS_DENIED";
      case ProfileDeletionBlockedException _ -> "PROFILE_DELETION_BLOCKED";
      case ProfileManagementDeniedException _ -> "PROFILE_MANAGEMENT_DENIED";
      case ProfileManagerInvariantException _ -> "PROFILE_MANAGER_INVARIANT";
      case ProfileSafetyViolationException _ -> "PROFILE_SAFETY_VIOLATION";
      case KidProfileManagerRequiredException _ -> "KID_PROFILE_MANAGER_REQUIRED";
      case ServerAdministrationDeniedException _ -> "SERVER_ADMINISTRATION_DENIED";
      case DataIntegrityViolationException violation when hasSqlState(violation, "23514") ->
          "PORTABLE_IDENTITY_INVARIANT_VIOLATION";
      case DataAccessException _ -> "DATABASE_OPERATION_FAILED";
      case IllegalArgumentException _ -> "INVALID_INPUT";
      default -> null;
    };
  }

  private static String messageFor(Throwable exception) {
    if (exception instanceof DataIntegrityViolationException violation
        && hasSqlState(violation, "23514")) {
      return "The requested change violates a profile or household safety invariant.";
    }
    if (exception instanceof DataAccessException) {
      return "The requested change could not be persisted.";
    }
    if (exception instanceof IllegalArgumentException) {
      return "The request contains an invalid value.";
    }
    return exception.getMessage();
  }

  private static boolean hasSqlState(Throwable exception, String expectedState) {
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (var current = exception;
        current != null && visited.add(current);
        current = current.getCause()) {
      if (current instanceof SQLException sqlException
          && sqlExceptionChainContains(sqlException, expectedState)) {
        return true;
      }
    }
    return false;
  }

  private static boolean sqlExceptionChainContains(SQLException exception, String expectedState) {
    for (var current = exception; current != null; current = current.getNextException()) {
      if (expectedState.equals(current.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
