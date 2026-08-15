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

  /**
   * Converts recognized data-fetching exceptions into GraphQL error results and delegates unrecognized exceptions.
   *
   * @param handlerParameters the exception and data-fetching environment
   * @return the GraphQL error result or the delegated handler result
   */
  @Override
  public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
      DataFetcherExceptionHandlerParameters handlerParameters) {
    var exception = unwrap(handlerParameters.getException());
    var extensions = extensionsFor(exception);

    if (extensions == null) {
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

  /**
   * Removes a {@link CompletionException} wrapper when it has a cause.
   *
   * @param exception the exception to unwrap
   * @return the underlying cause, or the original exception when no cause is available
   */
  private static Throwable unwrap(Throwable exception) {
    if (exception instanceof CompletionException completion && completion.getCause() != null) {
      return completion.getCause();
    }
    return exception;
  }

  /**
   * Creates GraphQL extensions for a recognized exception.
   *
   * @param exception the exception to map to an application error code
   * @return an extension map containing the error code, or {@code null} when no code is mapped
   */
  private static Map<String, Object> extensionsFor(Throwable exception) {
    var code = codeFor(exception);
    return code == null ? null : Map.of("code", code);
  }

  /**
   * Maps a recognized exception to its machine-readable error code.
   *
   * @param exception the exception to classify
   * @return the corresponding error code, or {@code null} when no code is defined
   */
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

  /**
   * Selects the client-facing message for an exception.
   *
   * @param exception the exception whose message should be selected
   * @return a standardized message for recognized failures, or the exception's message otherwise
   */
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

  /**
   * Determines whether an exception or its cause chain contains an SQL exception with the specified SQL state.
   *
   * @param exception    the exception whose cause chain to inspect
   * @param expectedState the SQL state to find
   * @return {@code true} if a matching SQL state is found, {@code false} otherwise
   */
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

  /**
   * Determines whether an SQL exception chain contains the specified SQL state.
   *
   * @param exception    the first exception in the chain
   * @param expectedState the SQL state to find
   * @return {@code true} if the chain contains the expected SQL state, {@code false} otherwise
   */
  private static boolean sqlExceptionChainContains(SQLException exception, String expectedState) {
    for (var current = exception; current != null; current = current.getNextException()) {
      if (expectedState.equals(current.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
