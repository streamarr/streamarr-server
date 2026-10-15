package com.streamarr.server.graphql;

import com.netflix.graphql.types.errors.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.Map;

/** GraphQL errors that carry Streamarr's machine-readable {@code errorType} and {@code code}. */
public final class CodedGraphQlErrors {

  private CodedGraphQlErrors() {}

  public static GraphQLError withCode(ErrorType errorType, String code, String message) {
    return GraphqlErrorBuilder.newError()
        .message(message)
        .errorType(errorType)
        .extensions(
            Map.of(
                StreamarrDataFetcherExceptionHandler.ERROR_TYPE,
                errorType.name(),
                StreamarrDataFetcherExceptionHandler.CODE,
                code))
        .build();
  }
}
