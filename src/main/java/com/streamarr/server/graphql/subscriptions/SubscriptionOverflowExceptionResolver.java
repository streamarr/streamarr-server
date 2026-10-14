package com.streamarr.server.graphql.subscriptions;

import com.netflix.graphql.types.errors.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.Map;
import org.springframework.graphql.execution.SubscriptionExceptionResolverAdapter;
import org.springframework.stereotype.Component;

/**
 * Ends an overflowing operation with a documented, recoverable error. Any other failure falls
 * through to the framework's sanitized "Subscription error".
 */
@Component
public class SubscriptionOverflowExceptionResolver extends SubscriptionExceptionResolverAdapter {

  public static final String SUBSCRIPTION_OVERFLOW = "SUBSCRIPTION_OVERFLOW";

  @Override
  protected GraphQLError resolveToSingleError(Throwable exception) {
    if (!(exception instanceof SubscriptionOverflowException overflow)) {
      return null;
    }

    return GraphqlErrorBuilder.newError()
        .message(overflow.getMessage())
        .errorType(ErrorType.UNAVAILABLE)
        .extensions(
            Map.of("errorType", ErrorType.UNAVAILABLE.name(), "code", SUBSCRIPTION_OVERFLOW))
        .build();
  }
}
