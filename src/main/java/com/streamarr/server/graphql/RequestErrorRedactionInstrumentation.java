package com.streamarr.server.graphql;

import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.InvalidSyntaxError;
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
import graphql.parser.ParserOptions;
import graphql.validation.ValidationError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Keeps request values out of the errors graphql-java raises before any data fetcher runs, for a
 * document that does not parse or does not pass validation. Their messages quote what the request
 * wrote, and that may be a credential: a share code, an invitation code, a PIN. A syntax error
 * keeps its line and column and leaves out the token the parser stopped at, in its message and
 * beside it, and the line that token stood on. Every value written under the node a validation
 * error points at becomes {@code 'REDACTED'} in its message, so the message still names the
 * argument and what is wrong with it. When those values would print too long to look for, the
 * message names only the kind of error and the path to its field, and the error keeps its location.
 * It runs first, so that every instrumentation after it, among them DGS's, which turns these errors
 * into typed errors that keep the message, reads the redacted message.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestErrorRedactionInstrumentation extends SimplePerformantInstrumentation {

  private static final String REDACTED = "'REDACTED'";

  // Far more than any hand-written argument's values print, and cheap to print.
  private static final long QUOTED_LENGTH_LIMIT = 1 << 14;

  // graphql-java parses a request with the parser options its context holds, and with its
  // operation defaults when it holds none.
  @Override
  public ExecutionInput instrumentExecutionInput(
      ExecutionInput executionInput,
      InstrumentationExecutionParameters parameters,
      InstrumentationState state) {
    var context = executionInput.getGraphQLContext();
    var parserOptions =
        Optional.ofNullable(context.<ParserOptions>get(ParserOptions.class))
            .orElseGet(ParserOptions::getDefaultOperationParserOptions);
    context.put(
        ParserOptions.class,
        parserOptions.transform(options -> options.redactTokenParserErrorMessages(true)));
    return executionInput;
  }

  @Override
  public CompletableFuture<ExecutionResult> instrumentExecutionResult(
      ExecutionResult executionResult,
      InstrumentationExecutionParameters parameters,
      InstrumentationState state) {
    if (executionResult.getErrors().isEmpty()) {
      return CompletableFuture.completedFuture(executionResult);
    }

    var descriptions = descriptionsWithoutValues(executionResult, parameters);
    var errors =
        executionResult.getErrors().stream().map(error -> redacted(error, descriptions)).toList();
    return CompletableFuture.completedFuture(
        executionResult.transform(builder -> builder.errors(errors)));
  }

  private static Function<ValidationError, String> descriptionsWithoutValues(
      ExecutionResult executionResult, InstrumentationExecutionParameters parameters) {
    return quotedValuesAt(executionResult, parameters)
        .<Function<ValidationError, String>>map(
            quotedValues -> rejected -> withoutQuotedValues(rejected, quotedValues))
        .orElse(RequestErrorRedactionInstrumentation::withoutAnyValue);
  }

  private static Optional<Map<SourceLocation, List<String>>> quotedValuesAt(
      ExecutionResult executionResult, InstrumentationExecutionParameters parameters) {
    var locations =
        executionResult.getErrors().stream()
            .filter(ValidationError.class::isInstance)
            .flatMap(error -> error.getLocations().stream())
            .collect(Collectors.toSet());
    if (locations.isEmpty()) {
      return Optional.of(Map.of());
    }

    // A document that failed validation parsed first, so it parses again the same way.
    var document = ParseAndValidate.parse(parameters.getExecutionInput()).getDocument();
    return quotedValuesAt(document, locations);
  }

  private static GraphQLError redacted(
      GraphQLError error, Function<ValidationError, String> descriptions) {
    return switch (error) {
      case ValidationError rejected -> described(rejected, descriptions.apply(rejected));
      case InvalidSyntaxError unparsed -> withoutOffendingToken(unparsed);
      default -> error;
    };
  }

  // An invalid unicode escape quotes itself in the message whatever the parser options say, and a
  // syntax error may carry the token the parser stopped at and a preview of its line beside the
  // message.
  private static GraphQLError withoutOffendingToken(InvalidSyntaxError unparsed) {
    var message =
        Optional.ofNullable(unparsed.getOffendingToken())
            .map(token -> unparsed.getMessage().replace("'" + token + "'", REDACTED))
            .orElseGet(unparsed::getMessage);
    return GraphqlErrorBuilder.newError()
        .errorType(ErrorType.InvalidSyntax)
        .message(message)
        .locations(unparsed.getLocations())
        .build();
  }

  private static String withoutQuotedValues(
      ValidationError rejected, Map<SourceLocation, List<String>> quotedValues) {
    var quoted =
        rejected.getLocations().stream()
            .flatMap(location -> quotedValues.getOrDefault(location, List.of()).stream())
            .toList();
    return withoutQuotes(rejected.getDescription(), quoted);
  }

  private static String withoutQuotes(String message, List<String> quoted) {
    // Longest first, so that a value is replaced whole before any value written inside it.
    var replaced =
        quoted.stream()
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    var redacted = message;
    for (var quotedValue : replaced) {
      redacted = redacted.replace(quotedValue, REDACTED);
    }

    return redacted;
  }

  private static String withoutAnyValue(ValidationError rejected) {
    return "Validation error (%s@[%s]) : the values written here are too long to quote"
        .formatted(rejected.getValidationErrorType(), String.join("/", rejected.getQueryPath()));
  }

  private static GraphQLError described(ValidationError rejected, String description) {
    return ValidationError.newValidationError()
        .validationErrorType(rejected.getValidationErrorType())
        .sourceLocations(rejected.getLocations())
        .queryPath(rejected.getQueryPath())
        .extensions(rejected.getExtensions())
        .description(description)
        .build();
  }

  private static Optional<Map<SourceLocation, List<String>>> quotedValuesAt(
      Document document, Set<SourceLocation> locations) {
    var quoting = new Quoting();
    var quotedValues = new HashMap<SourceLocation, List<String>>();
    var located =
        nodes(document).filter(node -> locations.contains(node.getSourceLocation())).toList();
    for (var node : located) {
      var quoted = quotedValues.computeIfAbsent(node.getSourceLocation(), _ -> new ArrayList<>());
      if (!quoting.quote(node, quoted)) {
        return Optional.empty();
      }
    }

    return Optional.of(quotedValues);
  }

  private static Stream<Node<?>> nodes(Node<?> node) {
    return Stream.concat(
        Stream.of(node),
        node.getChildren().stream().flatMap(RequestErrorRedactionInstrumentation::nodes));
  }

  // A value prints every value inside it again, so printing each value of a literal nested n deep
  // prints that literal n times over. Values are quoted innermost first, under one limit for the
  // whole request, so that a value is printed only once all the values inside it fit.
  private static final class Quoting {

    private long left = QUOTED_LENGTH_LIMIT;

    // Whether every value under the node fits within the limit.
    private boolean quote(Node<?> node, List<String> quoted) {
      for (var child : node.getChildren()) {
        if (!quote(child, quoted)) {
          return false;
        }
      }

      if (!(node instanceof Value<?> value)) {
        return true;
      }

      // graphql-java quotes a rejected value as its syntax node, and a scalar's own message may
      // quote its literal text.
      return quoteEach(Stream.concat(Stream.of(value.toString()), literal(value)), quoted);
    }

    // Whether the texts, each quoted, still fit within the limit.
    private boolean quoteEach(Stream<String> texts, List<String> quoted) {
      var quotes = texts.map(text -> "'" + text + "'").toList();
      left -= quotes.stream().mapToLong(String::length).sum();
      quoted.addAll(quotes);
      return left >= 0;
    }

    private static Stream<String> literal(Value<?> value) {
      return switch (value) {
        case StringValue string -> Stream.of(string.getValue());
        case IntValue integer -> Stream.of(integer.getValue().toString());
        case FloatValue decimal -> Stream.of(decimal.getValue().toString());
        case BooleanValue bool -> Stream.of(String.valueOf(bool.isValue()));
        case EnumValue enumValue -> Stream.of(enumValue.getName());
        default -> Stream.empty();
      };
    }
  }
}
