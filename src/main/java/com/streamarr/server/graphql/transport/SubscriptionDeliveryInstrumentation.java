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
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderRegistry;
import org.dataloader.registries.ScheduledDataLoaderRegistry;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Executes each value a subscription delivers against current data, as the caller who subscribed,
 * in delivery order, without letting one slow value hold back the values after it.
 *
 * <p>The values arrive on delivery threads, which carry no security context. Each field of a
 * delivered value runs with the security context captured when the operation subscribed, so field
 * resolvers read the subscriber's identity and Profile as they do in a query.
 *
 * <p>All values of one operation run against the operation's one DataLoader registry, and the
 * executions of two values may overlap. A cached loader would answer a later value with an earlier
 * value's data, and clearing the cache between values would race with an overlapping execution. So
 * WebSocket requests, which carry only subscriptions, get DataLoaders without caching; batching
 * still applies within one execution. Queries and mutations keep their cache.
 *
 * <p>graphql-java sends an operation's results in the order the values arrived, so a later value
 * waits for an earlier one. Each asynchronous field of a delivered value is bounded by {@link
 * SubscriptionProperties#deliveryFetchTimeout()}: a slower field resolves as an error on that value
 * instead of stalling the operation.
 */
@Component
class SubscriptionDeliveryInstrumentation extends SimplePerformantInstrumentation {

  private static final String SUBSCRIBER_SECURITY_CONTEXT = "streamarr.subscriberSecurityContext";

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

    context.put(SUBSCRIBER_SECURITY_CONTEXT, captureSecurityContext());
    context.put(SubscriptionExecutionStrategy.KEEP_SUBSCRIPTION_EVENTS_ORDERED, true);
    disableDataLoaderCachingForWebSocketRequests(executionInput.getDataLoaderRegistry());
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

    SecurityContext subscriberContext =
        executionContext
            .getGraphQLContext()
            .getOrDefault(SUBSCRIBER_SECURITY_CONTEXT, SecurityContextHolder.createEmptyContext());
    return environment -> {
      var deliveryThreadContext = SecurityContextHolder.getContext();
      SecurityContextHolder.setContext(subscriberContext);
      try {
        return boundByDeliveryFetchTimeout(dataFetcher.get(environment));
      } finally {
        SecurityContextHolder.setContext(deliveryThreadContext);
      }
    };
  }

  private static SecurityContext captureSecurityContext() {
    var captured = SecurityContextHolder.createEmptyContext();
    captured.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
    return captured;
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

  private static void disableDataLoaderCachingForWebSocketRequests(DataLoaderRegistry registry) {
    registry
        .getDataLoadersMap()
        .forEach((name, loader) -> replace(registry, name, uncached(loader)));
  }

  // DGS builds a ScheduledDataLoaderRegistry, which keys each dispatch predicate by DataLoader
  // instance. A replacement without its predicate falls back to the registry's default, which
  // never dispatches it.
  private static void replace(
      DataLoaderRegistry registry, String name, DataLoader<?, ?> replacement) {
    if (!(registry instanceof ScheduledDataLoaderRegistry scheduled)) {
      registry.register(name, replacement);
      return;
    }

    var original = scheduled.getDataLoader(name);
    var predicate =
        scheduled
            .getDataLoaderPredicates()
            .getOrDefault(original, scheduled.getDispatchPredicate());
    scheduled.unregister(name);
    scheduled.register(name, replacement, predicate);
  }

  private static <K, V> DataLoader<K, V> uncached(DataLoader<K, V> loader) {
    var options = loader.getOptions().transform(builder -> builder.setCachingEnabled(false));
    return loader.transform(builder -> builder.options(options));
  }
}
