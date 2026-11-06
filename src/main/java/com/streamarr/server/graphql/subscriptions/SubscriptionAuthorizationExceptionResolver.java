package com.streamarr.server.graphql.subscriptions;

import com.netflix.graphql.types.errors.ErrorType;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.graphql.CodedGraphQlErrors;
import graphql.GraphQLError;
import org.springframework.graphql.execution.SubscriptionExceptionResolverAdapter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Ends an operation whose subscriber lost its authority with the code that a query gets: FORBIDDEN
 * when the decision denies it, and AUTHORIZATION_UNAVAILABLE when no decision could be made.
 */
@Component
class SubscriptionAuthorizationExceptionResolver extends SubscriptionExceptionResolverAdapter {

  @Override
  protected GraphQLError resolveToSingleError(Throwable exception) {
    return switch (exception) {
      case AccessDeniedException denied ->
          CodedGraphQlErrors.withCode(
              ErrorType.PERMISSION_DENIED, "FORBIDDEN", denied.getMessage());
      case AuthorizationUnavailableException unavailable ->
          CodedGraphQlErrors.withCode(
              ErrorType.UNAVAILABLE, "AUTHORIZATION_UNAVAILABLE", unavailable.getMessage());
      default -> null;
    };
  }
}
