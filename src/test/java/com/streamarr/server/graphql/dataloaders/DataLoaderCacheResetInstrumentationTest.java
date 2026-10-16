package com.streamarr.server.graphql.dataloaders;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("DataLoader Cache Reset Instrumentation Tests")
class DataLoaderCacheResetInstrumentationTest {

  private static final String SCHEMA =
      """
      type Query { state: Int! }
      type Mutation { setState(value: Int!): Snapshot! }
      type Snapshot { state: Int! }
      """;
  private static final String STATE_LOADER = "state";
  private static final String STATE_KEY = "current";

  private final AtomicInteger storedState = new AtomicInteger();
  private final List<String> loadedKeys = new ArrayList<>();

  @Test
  @DisplayName("Should read each mutation field's own write when two fields load the same key")
  void shouldReadEachMutationFieldsOwnWriteWhenTwoFieldsLoadTheSameKey() {
    var result =
        execute(
            """
            mutation {
              first: setState(value: 1) { state }
              second: setState(value: 2) { state }
            }
            """);

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.<Map<String, Object>>getData())
        .isEqualTo(Map.of("first", Map.of("state", 1), "second", Map.of("state", 2)));
  }

  @Test
  @DisplayName("Should load a key once when two query fields read the same key")
  void shouldLoadAKeyOnceWhenTwoQueryFieldsReadTheSameKey() {
    var result = execute("query { a: state b: state }");

    assertThat(result.getErrors()).isEmpty();
    assertThat(loadedKeys).containsExactly(STATE_KEY);
  }

  private ExecutionResult execute(String operation) {
    var wiring =
        RuntimeWiring.newRuntimeWiring()
            .type("Query", type -> type.dataFetcher("state", this::loadState))
            .type(
                "Mutation",
                type ->
                    type.dataFetcher(
                        "setState",
                        env -> {
                          storedState.set(env.<Integer>getArgument("value"));
                          return Map.of();
                        }))
            .type("Snapshot", type -> type.dataFetcher("state", this::loadState))
            .build();
    var schema =
        new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SCHEMA), wiring);
    var graphQl =
        GraphQL.newGraphQL(schema)
            .instrumentation(new DataLoaderCacheResetInstrumentation())
            .build();
    var stateLoader =
        DataLoaderFactory.<String, Integer>newDataLoader(
            keys -> {
              loadedKeys.addAll(keys);
              return CompletableFuture.completedFuture(
                  keys.stream().map(_ -> storedState.get()).toList());
            });
    var registry = DataLoaderRegistry.newRegistry().register(STATE_LOADER, stateLoader).build();

    return graphQl.execute(
        ExecutionInput.newExecutionInput(operation).dataLoaderRegistry(registry).build());
  }

  private CompletableFuture<Integer> loadState(DataFetchingEnvironment env) {
    return env.<String, Integer>getDataLoader(STATE_LOADER).load(STATE_KEY);
  }
}
