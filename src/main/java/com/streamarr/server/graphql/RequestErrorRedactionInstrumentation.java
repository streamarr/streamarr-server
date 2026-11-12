package com.streamarr.server.graphql;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.ParseAndValidate;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.language.BooleanValue;
import graphql.language.Document;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.Node;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.validation.ValidationError;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Keeps argument values out of validation errors. graphql-java rejects a document before any data
 * fetcher runs and quotes the value it rejects, and that value may be a credential: a share code,
 * an invitation code, a PIN. Every value written under the node an error points at becomes {@code
 * 'REDACTED'} in its message, so the message still names the argument and what is wrong with it. It
 * runs first, so that every instrumentation after it, among them DGS's, which turns these errors
 * into typed errors that keep the message, reads the redacted message.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestErrorRedactionInstrumentation extends SimplePerformantInstrumentation {

  private static final String REDACTED = "'REDACTED'";

  @Override
  public CompletableFuture<ExecutionResult> instrumentExecutionResult(
      ExecutionResult executionResult,
      InstrumentationExecutionParameters parameters,
      InstrumentationState state) {
    var locations =
        executionResult.getErrors().stream()
            .filter(ValidationError.class::isInstance)
            .flatMap(error -> error.getLocations().stream())
            .collect(Collectors.toSet());
    if (locations.isEmpty()) {
      return CompletableFuture.completedFuture(executionResult);
    }

    // A document that failed validation parsed first, so it parses again the same way.
    var document = ParseAndValidate.parse(parameters.getExecutionInput()).getDocument();
    var quotedValues = quotedValuesAt(document, locations);
    var errors =
        executionResult.getErrors().stream().map(error -> redacted(error, quotedValues)).toList();
    return CompletableFuture.completedFuture(
        executionResult.transform(builder -> builder.errors(errors)));
  }

  private static GraphQLError redacted(
      GraphQLError error, Map<SourceLocation, List<String>> quotedValues) {
    if (!(error instanceof ValidationError rejected)) {
      return error;
    }

    // Longest first, so that a value is replaced whole before any value written inside it.
    var replaced =
        rejected.getLocations().stream()
            .flatMap(location -> quotedValues.getOrDefault(location, List.of()).stream())
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    var message = rejected.getDescription();
    for (var quotedValue : replaced) {
      message = message.replace(quotedValue, REDACTED);
    }

    return ValidationError.newValidationError()
        .validationErrorType(rejected.getValidationErrorType())
        .sourceLocations(rejected.getLocations())
        .queryPath(rejected.getQueryPath())
        .extensions(rejected.getExtensions())
        .description(message)
        .build();
  }

  private static Map<SourceLocation, List<String>> quotedValuesAt(
      Document document, Set<SourceLocation> locations) {
    return nodes(document)
        .filter(node -> locations.contains(node.getSourceLocation()))
        .collect(
            Collectors.groupingBy(
                Node::getSourceLocation,
                Collectors.flatMapping(
                    node -> nodes(node).flatMap(RequestErrorRedactionInstrumentation::quoted),
                    Collectors.toList())));
  }

  private static Stream<Node<?>> nodes(Node<?> node) {
    return Stream.concat(
        Stream.of(node),
        node.getChildren().stream().flatMap(RequestErrorRedactionInstrumentation::nodes));
  }

  // graphql-java quotes a rejected value as its syntax node, and a scalar's own message may quote
  // its literal text.
  private static Stream<String> quoted(Node<?> node) {
    if (!(node instanceof Value<?> value)) {
      return Stream.empty();
    }

    var literal =
        switch (value) {
          case StringValue string -> Stream.of(string.getValue());
          case IntValue integer -> Stream.of(integer.getValue().toString());
          case FloatValue decimal -> Stream.of(decimal.getValue().toString());
          case BooleanValue bool -> Stream.of(String.valueOf(bool.isValue()));
          case EnumValue enumValue -> Stream.of(enumValue.getName());
          default -> Stream.<String>empty();
        };
    return Stream.concat(Stream.of(value.toString()), literal).map(text -> "'" + text + "'");
  }
}
