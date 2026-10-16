package com.streamarr.server.graphql.transport;

import com.streamarr.server.graphql.subscriptions.SubscriptionProperties;
import graphql.ExecutionInput;
import graphql.execution.SubscriptionExecutionStrategy;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.language.OperationDefinition.Operation;
import graphql.schema.DataFetcher;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Executes each value a subscription delivers in delivery order, without letting one slow value
 * hold back the values after it.
 *
 * <p>graphql-java sends an operation's results in the order the values arrived, so a later value
 * waits for every earlier value. Each asynchronous field of a delivered value is bounded by {@link
 * SubscriptionProperties#deliveryFetchTimeout()}: a slower field resolves as an error on that value
 * instead of stalling the operation.
 */
@Component
class SubscriptionDeliveryInstrumentation extends SimplePerformantInstrumentation {

  private final Duration deliveryFetchTimeout;

  SubscriptionDeliveryInstrumentation(SubscriptionProperties properties) {
    this.deliveryFetchTimeout = properties.deliveryFetchTimeout();
  }

  @Override
  public ExecutionInput instrumentExecutionInput(
      ExecutionInput executionInput,
      InstrumentationExecutionParameters parameters,
      InstrumentationState state) {
    var context = executionInput.getGraphQLContext();
    if (!GraphQlTransport.WEBSOCKET.equals(context.get(GraphQlTransport.class))) {
      return executionInput;
    }

    context.put(SubscriptionExecutionStrategy.KEEP_SUBSCRIPTION_EVENTS_ORDERED, true);
    return executionInput;
  }

  @Override
  public DataFetcher<?> instrumentDataFetcher(
      DataFetcher<?> dataFetcher,
      InstrumentationFieldFetchParameters parameters,
      InstrumentationState state) {
    var executionContext = parameters.getExecutionContext();
    var operation = executionContext.getOperationDefinition().getOperation();
    var isRootField = parameters.getExecutionStepInfo().getPath().getLevel() == 1;
    if (operation != Operation.SUBSCRIPTION || isRootField) {
      return dataFetcher;
    }

    return environment -> boundByDeliveryFetchTimeout(dataFetcher.get(environment));
  }

  private Object boundByDeliveryFetchTimeout(Object value) {
    if (!(value instanceof CompletionStage<?> stage)) {
      return value;
    }

    return stage
        .toCompletableFuture()
        .copy()
        .orTimeout(deliveryFetchTimeout.toMillis(), TimeUnit.MILLISECONDS);
  }
}
