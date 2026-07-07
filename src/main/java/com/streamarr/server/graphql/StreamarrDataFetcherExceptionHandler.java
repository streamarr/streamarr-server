package com.streamarr.server.graphql;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Maps identity/authorization failures to machine codes in extensions.code — the GraphQL side of
 * the client contract (PROFILE_REQUIRED routes to the picker, not an error page). Everything else
 * falls through to the DGS default.
 */
@Component
public class StreamarrDataFetcherExceptionHandler implements DataFetcherExceptionHandler {

  private final DataFetcherExceptionHandler delegate =
      new com.netflix.graphql.dgs.exceptions.DefaultDataFetcherExceptionHandler();

  @Override
  public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
      DataFetcherExceptionHandlerParameters handlerParameters) {
    var exception = unwrap(handlerParameters.getException());
    var code = codeFor(exception);

    if (code == null) {
      return delegate.handleException(handlerParameters);
    }

    var error =
        GraphqlErrorBuilder.newError(handlerParameters.getDataFetchingEnvironment())
            .message(exception.getMessage())
            .extensions(Map.of("code", code))
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

  private static String codeFor(Throwable exception) {
    return switch (exception) {
      case ProfileRequiredException _ -> "PROFILE_REQUIRED";
      case HouseholdRequiredException _ -> "HOUSEHOLD_REQUIRED";
      case AuthenticationRequiredException _ -> "AUTHENTICATION_REQUIRED";
      case AccessDeniedException _ -> "FORBIDDEN";
      default -> null;
    };
  }
}
