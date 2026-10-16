package com.streamarr.server.graphql.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.graphql.subscriptions.SubscriptionProperties;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.SubscriptionExecutionStrategy;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderRegistry;
import org.dataloader.MappedBatchLoader;
import org.dataloader.registries.DispatchPredicate;
import org.dataloader.registries.ScheduledDataLoaderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Subscription Delivery Instrumentation Tests")
class SubscriptionDeliveryInstrumentationTest {

  private static final Duration DELIVERY_FETCH_TIMEOUT = Duration.ofMillis(100);
  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final String LOADER = "counter";

  private final SubscriptionDeliveryInstrumentation instrumentation =
      new SubscriptionDeliveryInstrumentation(
          new SubscriptionProperties(64, 32, DataSize.ofMegabytes(1), DELIVERY_FETCH_TIMEOUT));

  @Test
  @DisplayName("Should load the key again for each request when the request uses the WebSocket")
  void shouldLoadKeyAgainForEachRequestWhenRequestUsesWebSocket() {
    var batches = new AtomicInteger();
    var registry = registryCountingBatches(batches);

    instrumentation.instrumentExecutionInput(
        input(registry, GraphQlTransport.WEBSOCKET), null, null);

    assertThat(loadAndDispatch(registry)).isEqualTo(1);
    assertThat(loadAndDispatch(registry)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should keep dispatching each loader when a scheduled registry dispatches by loader predicate")
  void shouldKeepDispatchingEachLoaderWhenScheduledRegistryDispatchesByLoaderPredicate() {
    var batches = new AtomicInteger();
    var registry =
        ScheduledDataLoaderRegistry.newScheduledRegistry()
            .dispatchPredicate(DispatchPredicate.DISPATCH_NEVER)
            .register(LOADER, countingLoader(batches), DispatchPredicate.DISPATCH_ALWAYS)
            .build();

    try (registry) {
      instrumentation.instrumentExecutionInput(
          input(registry, GraphQlTransport.WEBSOCKET), null, null);

      assertThat(loadAndDispatchAll(registry)).isEqualTo(1);
      assertThat(loadAndDispatchAll(registry)).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("Should answer the key from the cache when the request uses HTTP")
  void shouldAnswerKeyFromCacheWhenRequestUsesHttp() {
    var batches = new AtomicInteger();
    var registry = registryCountingBatches(batches);

    instrumentation.instrumentExecutionInput(input(registry, GraphQlTransport.HTTP), null, null);

    assertThat(loadAndDispatch(registry)).isEqualTo(1);
    assertThat(loadAndDispatch(registry)).isEqualTo(1);
  }

  @Test
  @DisplayName("Should keep subscription values in order when the request uses the WebSocket")
  void shouldKeepSubscriptionValuesInOrderWhenRequestUsesWebSocket() {
    var input = input(new DataLoaderRegistry(), GraphQlTransport.WEBSOCKET);

    instrumentation.instrumentExecutionInput(input, null, null);

    assertThat(
            input
                .getGraphQLContext()
                .getBoolean(SubscriptionExecutionStrategy.KEEP_SUBSCRIPTION_EVENTS_ORDERED))
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should deliver the value with an error when a field of a delivered value outlasts the"
          + " delivery fetch timeout")
  void shouldDeliverValueWithErrorWhenFieldOfDeliveredValueOutlastsDeliveryFetchTimeout() {
    var graphQl = graphQl(_ -> new CompletableFuture<String>());

    var delivered = firstDelivered(graphQl.execute("subscription { change { value } }"));

    assertThat(delivered.getErrors()).isNotEmpty();
    assertThat(delivered.<Map<String, Map<String, Object>>>getData().get("change"))
        .containsEntry("value", null);
  }

  @Test
  @DisplayName(
      "Should deliver the next value when a nullable field of an earlier value outlasts the"
          + " delivery fetch timeout")
  void shouldDeliverNextValueWhenNullableFieldOfEarlierValueOutlastsDeliveryFetchTimeout() {
    var fetches = new AtomicInteger();
    var graphQl =
        graphQl(
            _ ->
                fetches.incrementAndGet() == 1
                    ? new CompletableFuture<>()
                    : CompletableFuture.completedFuture("current"));
    var subscription =
        ExecutionInput.newExecutionInput("subscription { change { value } }")
            .graphQLContext(Map.of(GraphQlTransport.class, GraphQlTransport.WEBSOCKET))
            .build();
    Publisher<ExecutionResult> values = graphQl.execute(subscription).getData();

    var delivered = Flux.from(values).collectList().block(BOUND);

    assertThat(delivered).hasSize(2);
    assertThat(delivered.getFirst().getErrors()).isNotEmpty();
    assertThat(delivered.getLast().getErrors()).isEmpty();
    assertThat(delivered.getLast().<Map<String, Map<String, Object>>>getData().get("change"))
        .containsEntry("value", "current");
  }

  @Test
  @DisplayName(
      "Should resolve a delivered value's fields as the subscriber when the value arrives on"
          + " another thread")
  void shouldResolveDeliveredValuesFieldsAsSubscriberWhenValueArrivesOnAnotherThread() {
    var graphQl =
        graphQl(
            _ ->
                CompletableFuture.completedFuture(
                    SecurityContextHolder.getContext().getAuthentication().getName()),
            Flux.just(Map.of()).publishOn(Schedulers.boundedElastic()));
    var subscription =
        ExecutionInput.newExecutionInput("subscription { change { value } }")
            .graphQLContext(Map.of(GraphQlTransport.class, GraphQlTransport.WEBSOCKET))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("subscriber", null));
    ExecutionResult result;
    try {
      result = graphQl.execute(subscription);
    } finally {
      SecurityContextHolder.clearContext();
    }

    var delivered = firstDelivered(result);

    assertThat(delivered.getErrors()).isEmpty();
    assertThat(delivered.<Map<String, Map<String, Object>>>getData().get("change"))
        .containsEntry("value", "subscriber");
  }

  @Test
  @DisplayName("Should leave a query field unbounded when it outlasts the delivery fetch timeout")
  void shouldLeaveQueryFieldUnboundedWhenItOutlastsDeliveryFetchTimeout() {
    var graphQl = graphQl(_ -> completedAfter(DELIVERY_FETCH_TIMEOUT.multipliedBy(3)));

    var result = graphQl.execute("{ change { value } }");

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.<Map<String, Map<String, Object>>>getData().get("change"))
        .containsEntry("value", "late");
  }

  private GraphQL graphQl(Function<Object, CompletableFuture<String>> value) {
    return graphQl(value, Flux.just(Map.of(), Map.of()));
  }

  private GraphQL graphQl(Function<Object, CompletableFuture<String>> value, Flux<?> changes) {
    var schema =
        """
        type Query { change: Change }
        type Subscription { change: Change! }
        type Change { value: String }
        """;
    var wiring =
        RuntimeWiring.newRuntimeWiring()
            .type("Query", type -> type.dataFetcher("change", _ -> Map.of()))
            .type("Subscription", type -> type.dataFetcher("change", _ -> changes))
            .type("Change", type -> type.dataFetcher("value", value::apply))
            .build();
    var graphQlSchema =
        new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(schema), wiring);
    return GraphQL.newGraphQL(graphQlSchema).instrumentation(instrumentation).build();
  }

  private static CompletableFuture<String> completedAfter(Duration delay) {
    return CompletableFuture.supplyAsync(
        () -> "late", CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
  }

  private static ExecutionResult firstDelivered(ExecutionResult subscription) {
    Publisher<ExecutionResult> values = subscription.getData();
    return Flux.from(values).blockFirst(BOUND);
  }

  private static ExecutionInput input(DataLoaderRegistry registry, GraphQlTransport transport) {
    return ExecutionInput.newExecutionInput("{ __typename }")
        .graphQLContext(Map.of(GraphQlTransport.class, transport))
        .dataLoaderRegistry(registry)
        .build();
  }

  private static DataLoaderRegistry registryCountingBatches(AtomicInteger batches) {
    var registry = new DataLoaderRegistry();
    registry.register(LOADER, countingLoader(batches));
    return registry;
  }

  private static DataLoader<String, Integer> countingLoader(AtomicInteger batches) {
    MappedBatchLoader<String, Integer> loader =
        keys -> {
          var batch = batches.incrementAndGet();
          return CompletableFuture.completedFuture(
              keys.stream().collect(Collectors.toMap(Function.identity(), _ -> batch)));
        };
    return DataLoaderFactory.newMappedDataLoader(loader);
  }

  // Dispatches through the registry, so the registry's dispatch predicates apply.
  private static int loadAndDispatchAll(DataLoaderRegistry registry) {
    DataLoader<String, Integer> loader = registry.getDataLoader(LOADER);
    var value = loader.load("key");
    registry.dispatchAll();
    return value.orTimeout(BOUND.toMillis(), TimeUnit.MILLISECONDS).join();
  }

  // The value the batch that answered the load carried: a new batch means the key was loaded again.
  private static int loadAndDispatch(DataLoaderRegistry registry) {
    DataLoader<String, Integer> loader = registry.getDataLoader(LOADER);
    var value = loader.load("key");
    loader.dispatch();
    return value.orTimeout(BOUND.toMillis(), TimeUnit.MILLISECONDS).join();
  }
}
