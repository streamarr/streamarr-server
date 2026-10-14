package com.streamarr.server.graphql.subscriptions;

import com.netflix.graphql.types.errors.ErrorType;
import com.streamarr.server.graphql.CodedGraphQlErrors;
import graphql.GraphQLError;
import org.springframework.graphql.execution.SubscriptionExceptionResolverAdapter;
import org.springframework.stereotype.Component;

/**
 * Ends an overflowing operation with a documented, recoverable error. Any other failure falls
 * through to the framework's sanitized "Subscription error".
 */
@Component
class SubscriptionOverflowExceptionResolver extends SubscriptionExceptionResolverAdapter {

  @Override
  protected GraphQLError resolveToSingleError(Throwable exception) {
    if (!(exception instanceof SubscriptionOverflowException overflow)) {
      return null;
    }

    return CodedGraphQlErrors.withCode(
        ErrorType.UNAVAILABLE, "SUBSCRIPTION_OVERFLOW", overflow.getMessage());
  }
}
