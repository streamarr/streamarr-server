package com.streamarr.server.graphql.contract;

import static graphql.schema.FieldCoordinates.coordinates;
import static org.assertj.core.api.Assertions.assertThat;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.idl.SchemaGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.dao.QueryTimeoutException;
import reactor.core.publisher.Flux;

@Tag("UnitTest")
class WatchStateFailureIsolationTest {
  @Test
  @DisplayName(
      "Should preserve item updates and later events when the Continue Watching lookup fails")
  void shouldPreserveItemUpdatesAndLaterEventsWhenContinueWatchingLookupFails() throws IOException {
    String sdl;
    try (var files = Files.list(Path.of("src/main/resources/schema"))) {
      sdl =
          files
              .filter(path -> path.toString().endsWith(".graphqls"))
              .sorted()
              .map(WatchStateFailureIsolationTest::read)
              .collect(Collectors.joining("\n"));
    }

    var schema = SchemaGenerator.createdMockedSchema(sdl);
    var lookups = new AtomicInteger();
    var registry =
        GraphQLCodeRegistry.newCodeRegistry(schema.getCodeRegistry())
            .dataFetcher(
                coordinates("Subscription", "watchStateChanged"),
                (DataFetchingEnvironment _) -> Flux.just(Map.of(), Map.of()))
            .dataFetcher(
                coordinates("WatchStateChange", "items"),
                (DataFetchingEnvironment _) -> List.of(Map.of("id", "movie-1")))
            .dataFetcher(coordinates("Movie", "id"), (DataFetchingEnvironment _) -> "movie-1")
            .dataFetcher(
                coordinates("WatchStateChange", "continueWatching"),
                (DataFetchingEnvironment _) -> {
                  if (lookups.getAndIncrement() == 0)
                    throw new QueryTimeoutException("Unavailable");

                  return List.of();
                })
            .typeResolver("WatchStateItem", _ -> schema.getObjectType("Movie"))
            .build();
    var graphQl =
        GraphQL.newGraphQL(schema.transform(builder -> builder.codeRegistry(registry))).build();
    var result =
        graphQl.execute(
            """
        subscription { event: watchStateChanged {
          items { ... on Movie { id } }
          shelf: continueWatching { itemId }
        } }
        """);
    assertThat(result.getErrors()).isEmpty();
    Publisher<ExecutionResult> stream = result.getData();
    var events = Flux.from(stream).collectList().block(Duration.ofSeconds(5));

    assertThat(events).hasSize(2);
    assertThat(events.getFirst().getErrors())
        .singleElement()
        .satisfies(error -> assertThat(error.getPath()).containsExactly("event", "shelf"));
    assertThat(events.getFirst().<Map<String, Map<String, Object>>>getData().get("event"))
        .containsEntry("items", List.of(Map.of("id", "movie-1")))
        .containsEntry("shelf", null);
    assertThat(events.getLast().getErrors()).isEmpty();
    assertThat(events.getLast().<Map<String, Map<String, Object>>>getData().get("event"))
        .containsEntry("items", List.of(Map.of("id", "movie-1")))
        .containsEntry("shelf", List.of());
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
