package com.streamarr.server.graphql;

import com.netflix.graphql.dgs.exceptions.DefaultDataFetcherExceptionHandler;
import com.netflix.graphql.types.errors.ErrorType;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.exceptions.InvalidPaginationArgumentException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.exceptions.RetryAfterAware;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The top-level error contract (ADR 0026): every error raised while fetching data carries exactly
 * {@code errorType} (DGS's classification), {@code code} (Streamarr's stable refinement, repeating
 * {@code errorType} when nothing finer applies), {@code requestId} (correlates the sanitized
 * response with the server log), and {@code retryAfterSeconds} only for a throttled request.
 * Request gates and routine misses keep their own codes; anything unrecognized is delegated to the
 * DGS default, sanitized, and logged with the request id so the client never sees an internal
 * message. Expected domain rejections never come here — they are payload {@code userErrors}.
 */
@Slf4j
@Component
public class StreamarrDataFetcherExceptionHandler implements DataFetcherExceptionHandler {

  static final String ERROR_TYPE = "errorType";
  static final String CODE = "code";
  static final String REQUEST_ID = "requestId";
  static final String RETRY_AFTER_SECONDS = "retryAfterSeconds";
  static final String SANITIZED_MESSAGE = "The request could not be completed.";

  private final DataFetcherExceptionHandler delegate = new DefaultDataFetcherExceptionHandler();

  @Override
  public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
      DataFetcherExceptionHandlerParameters handlerParameters) {
    var exception = unwrap(handlerParameters.getException());
    var requestId = newRequestId();
    var classification = classify(exception);

    if (exception instanceof CompletionException) {
      log.error(
          "GraphQL data fetcher failed [requestId={}] path={}",
          requestId,
          handlerParameters.getPath(),
          exception);
      var error =
          GraphqlErrorBuilder.newError(handlerParameters.getDataFetchingEnvironment())
              .message(SANITIZED_MESSAGE)
              .errorType(ErrorType.INTERNAL)
              .extensions(
                  extensions(ErrorType.INTERNAL.name(), ErrorType.INTERNAL.name(), requestId))
              .build();
      return CompletableFuture.completedFuture(
          DataFetcherExceptionHandlerResult.newResult().error(error).build());
    }

    if (classification == null) {
      return delegate
          .handleException(handlerParameters)
          .thenApply(result -> sanitized(result, handlerParameters, requestId, exception));
    }

    var extensions = extensions(classification.errorType(), classification.code(), requestId);
    if (exception instanceof RetryAfterAware throttled) {
      extensions.put(RETRY_AFTER_SECONDS, retryAfterSeconds(throttled));
    }

    var error =
        GraphqlErrorBuilder.newError(handlerParameters.getDataFetchingEnvironment())
            .message(exception.getMessage())
            .errorType(classification.type())
            .extensions(extensions)
            .build();
    log.debug(
        "GraphQL request rejected [requestId={}] path={} code={}",
        requestId,
        handlerParameters.getPath(),
        classification.code());
    return CompletableFuture.completedFuture(
        DataFetcherExceptionHandlerResult.newResult().error(error).build());
  }

  private DataFetcherExceptionHandlerResult sanitized(
      DataFetcherExceptionHandlerResult delegated,
      DataFetcherExceptionHandlerParameters handlerParameters,
      String requestId,
      Throwable exception) {
    var builder = DataFetcherExceptionHandlerResult.newResult();
    for (var delegatedError : delegated.getErrors()) {
      var errorType = delegatedErrorType(delegatedError);
      var internal = ErrorType.INTERNAL.name().equals(errorType);
      if (internal) {
        log.error(
            "GraphQL data fetcher failed [requestId={}] path={}",
            requestId,
            handlerParameters.getPath(),
            exception);
      }

      builder.error(
          GraphqlErrorBuilder.newError(handlerParameters.getDataFetchingEnvironment())
              .message(internal ? SANITIZED_MESSAGE : delegatedError.getMessage())
              .errorType(ErrorType.valueOf(errorType))
              .extensions(extensions(errorType, errorType, requestId))
              .build());
    }

    return builder.build();
  }

  private static String delegatedErrorType(GraphQLError error) {
    var extensions = error.getExtensions();
    if (extensions != null && extensions.get(ERROR_TYPE) != null) {
      return extensions.get(ERROR_TYPE).toString();
    }

    return ErrorType.INTERNAL.name();
  }

  private static Map<String, Object> extensions(String errorType, String code, String requestId) {
    var extensions = new LinkedHashMap<String, Object>();
    extensions.put(ERROR_TYPE, errorType);
    extensions.put(CODE, code);
    extensions.put(REQUEST_ID, requestId);
    return extensions;
  }

  private static Throwable unwrap(Throwable exception) {
    if (exception instanceof CompletionException completion && completion.getCause() != null) {
      return completion.getCause();
    }

    return exception;
  }

  private static long retryAfterSeconds(RetryAfterAware throttled) {
    var retryAfter = throttled.retryAfter();
    if (retryAfter.isNegative() || retryAfter.isZero()) {
      return 0;
    }

    if (retryAfter.getNano() == 0 || retryAfter.getSeconds() == Long.MAX_VALUE) {
      return retryAfter.getSeconds();
    }

    return retryAfter.getSeconds() + 1;
  }

  private static Classification classify(Throwable exception) {
    return switch (exception) {
      case ProfileRequiredException _ ->
          new Classification(ErrorType.FAILED_PRECONDITION, "PROFILE_REQUIRED");
      case HouseholdRequiredException _ ->
          new Classification(ErrorType.FAILED_PRECONDITION, "HOUSEHOLD_REQUIRED");
      case AuthenticationRequiredException _ ->
          new Classification(ErrorType.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED");
      case AccessDeniedException _ -> new Classification(ErrorType.PERMISSION_DENIED, "FORBIDDEN");
      case AuthorizationUnavailableException _ ->
          new Classification(ErrorType.UNAVAILABLE, "AUTHORIZATION_UNAVAILABLE");
      case TooManyCredentialAttemptsException _ ->
          new Classification(ErrorType.UNAVAILABLE, "TOO_MANY_CREDENTIAL_ATTEMPTS");
      case RetryAfterAware _ -> new Classification(ErrorType.UNAVAILABLE, "TOO_MANY_ATTEMPTS");
      case SessionNotFoundException _ ->
          new Classification(ErrorType.NOT_FOUND, "SESSION_NOT_FOUND");
      // Query-side argument validation keeps its safe, displayable messages (ADR 0026 leaves
      // query errors unchanged); only unrecognized exceptions are sanitized.
      case InvalidIdException _ -> new Classification(ErrorType.BAD_REQUEST, "INVALID_INPUT");
      case InvalidPaginationArgumentException _ ->
          new Classification(ErrorType.BAD_REQUEST, "INVALID_INPUT");
      case InvalidCursorException _ -> new Classification(ErrorType.BAD_REQUEST, "INVALID_CURSOR");
      case UnsupportedMediaTypeException _ ->
          new Classification(ErrorType.FAILED_PRECONDITION, "UNSUPPORTED_MEDIA_TYPE");
      default -> null;
    };
  }

  private static String newRequestId() {
    return "req-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private record Classification(ErrorType type, String code) {

    String errorType() {
      return type.name();
    }
  }
}
