package com.streamarr.server.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.management.ThreadMXBean;
import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.parser.ParserOptions;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Request Error Redaction Instrumentation Tests")
class RequestErrorRedactionInstrumentationTest {

  private static final String SCHEMA =
      """
      type Query {
        lookUp(input: LookUpInput!): String
        page(first: Int, ratio: Float, exact: Boolean, mode: Mode): String
      }
      input LookUpInput { id: ID! code: String! }
      enum Mode { ON OFF }
      """;
  private static final String CODE = "BCDF-GHJK";

  private final GraphQLSchema schema =
      new SchemaGenerator()
          .makeExecutableSchema(
              new SchemaParser().parse(SCHEMA), RuntimeWiring.newRuntimeWiring().build());
  private final GraphQL graphQL =
      GraphQL.newGraphQL(schema)
          .instrumentation(new RequestErrorRedactionInstrumentation())
          .build();

  @ParameterizedTest
  @MethodSource("rejectedValues")
  @DisplayName("Should name what is wrong without the value when validation rejects a value")
  void shouldNameWhatIsWrongWithoutValueWhenValidationRejectsValue(
      String document, String value, String named) {
    var error = singleError(ExecutionInput.newExecutionInput(document).build());

    assertThat(error.getErrorType()).isEqualTo(ErrorType.ValidationError);
    assertThat(error.getMessage()).contains(named);
    assertThat(error.toSpecification().toString()).doesNotContain(value);
  }

  @Test
  @DisplayName("Should keep the value out of the message when the request asks for German")
  void shouldKeepValueOutOfMessageWhenRequestAsksForGerman() {
    var error =
        singleError(
            ExecutionInput.newExecutionInput("{ lookUp(input: {code: \"%s\"}) }".formatted(CODE))
                .locale(Locale.GERMAN)
                .build());

    assertThat(error.getMessage())
        .startsWith("Validierungsfehler")
        .contains("'[id]'")
        .doesNotContain(CODE);
  }

  @ParameterizedTest
  @MethodSource("unparsableDocuments")
  @DisplayName("Should keep the line and column without the value when a document does not parse")
  void shouldKeepLineAndColumnWithoutValueWhenDocumentDoesNotParse(String document) {
    var error = singleError(ExecutionInput.newExecutionInput(document).build());

    assertSyntaxErrorAtItsLineAndColumnWithout(error, CODE, CODE.replace("-", ""));
  }

  @ParameterizedTest
  @MethodSource("invalidUnicodeEscapes")
  @DisplayName(
      "Should keep the line and column without the escape when a string holds invalid unicode")
  void shouldKeepLineAndColumnWithoutEscapeWhenStringHoldsInvalidUnicode(
      String escape, String value) {
    var error =
        singleError(
            ExecutionInput.newExecutionInput(
                    "{ lookUp(input: {id: \"1\", code: \"%s\"}) }".formatted(escape))
                .build());

    assertSyntaxErrorAtItsLineAndColumnWithout(error, value);
  }

  @Test
  @DisplayName("Should keep the escape out of a syntax error when the request asks for German")
  void shouldKeepEscapeOutOfSyntaxErrorWhenRequestAsksForGerman() {
    var error =
        singleError(
            ExecutionInput.newExecutionInput("{ lookUp(input: {code: \"\\u{48261379}\"}) }")
                .locale(Locale.GERMAN)
                .build());

    var location = error.getLocations().getFirst();
    assertThat(error.getMessage())
        .contains("Zeile %d Spalte %d".formatted(location.getLine(), location.getColumn()))
        .doesNotContain("48261379");
  }

  @Test
  @DisplayName("Should keep the value out of a syntax error when the request asks for German")
  void shouldKeepValueOutOfSyntaxErrorWhenRequestAsksForGerman() {
    var error =
        singleError(
            ExecutionInput.newExecutionInput("{ lookUp(input: {code: \"%s}) }".formatted(CODE))
                .locale(Locale.GERMAN)
                .build());

    var location = error.getLocations().getFirst();
    assertThat(error.getMessage())
        .contains("Zeile %d Spalte %d".formatted(location.getLine(), location.getColumn()))
        .doesNotContain(CODE);
  }

  @Test
  @DisplayName("Should keep the parser limits a request sets when a document is too long to parse")
  void shouldKeepParserLimitsRequestSetsWhenDocumentIsTooLongToParse() {
    var limits = ParserOptions.newParserOptions().maxTokens(4).build();

    var error =
        singleError(
            ExecutionInput.newExecutionInput("{ lookUp(input: {code: \"%s\"}) }".formatted(CODE))
                .graphQLContext(Map.of(ParserOptions.class, limits))
                .build());

    assertThat(error.getErrorType()).isEqualTo(ErrorType.InvalidSyntax);
    assertThat(error.getMessage()).doesNotContain(CODE);
  }

  @ParameterizedTest
  @MethodSource("deeplyNestedValues")
  @DisplayName("Should redact at a cost in line with the request's size when a value nests deeply")
  void shouldRedactAtCostInLineWithRequestSizeWhenValueNestsDeeply(String document, String named) {
    var unredacted = GraphQL.newGraphQL(schema).build();

    var redactionCost =
        allocatedAnswering(graphQL, document) - allocatedAnswering(unredacted, document);

    var error = singleError(ExecutionInput.newExecutionInput(document).build());
    assertThat(error.getErrorType()).isEqualTo(ErrorType.ValidationError);
    assertThat(error.getMessage()).contains(named);
    assertThat(error.toSpecification().toString()).doesNotContain(CODE);
    // Reading the request again, and a few megabytes at most for quoting its values.
    assertThat(redactionCost).isLessThan(64L * document.length() + (8L << 20));
  }

  @Test
  @DisplayName("Should answer as before when the document passes validation")
  void shouldAnswerAsBeforeWhenDocumentPassesValidation() {
    var result = graphQL.execute("{ page(first: 1, mode: ON) }");

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.isDataPresent()).isTrue();
  }

  static Stream<Arguments> rejectedValues() {
    return Stream.of(
        Arguments.of(
            "{ lookUp(input: {id: \"1\", code: \"%s\", household: \"Home\"}) }".formatted(CODE),
            CODE,
            "'household'"),
        Arguments.of("{ lookUp(input: {code: \"%s\"}) }".formatted(CODE), CODE, "'[id]'"),
        Arguments.of("{ lookUp(input: {id: \"1\", code: 4826}) }", "4826", "'input.code'"),
        Arguments.of(
            "{ lookUp(input: {id: \"1\", code: %s}) }".formatted(nested(CODE, 3)),
            CODE,
            "'input.code'"),
        Arguments.of("{ page(first: 99999999999) }", "99999999999", "'first'"),
        Arguments.of("{ page(ratio: true) }", "true", "'ratio'"),
        Arguments.of("{ page(exact: 0.75) }", "0.75", "'exact'"),
        Arguments.of("{ page(mode: SOMETIMES) }", "SOMETIMES", "'mode'"),
        Arguments.of(
            """
            query ($input: LookUpInput = {id: "1", code: "%s", household: "Home"}) {
              lookUp(input: $input)
            }
            """
                .formatted(CODE),
            CODE,
            "'LookUpInput'"));
  }

  static Stream<Arguments> deeplyNestedValues() {
    var longCode = nested(CODE.repeat(11_000), 150);
    return Stream.of(
        Arguments.of("{ lookUp(input: {id: \"1\", code: %s}) }".formatted(longCode), "WrongType"),
        Arguments.of(
            """
            query ($input: LookUpInput = {id: "1", code: %s}) {
              lookUp(input: $input)
            }
            """
                .formatted(longCode),
            "BadValueForDefaultArg"),
        Arguments.of("{ page(first: 1, size: %s) }".formatted(longCode), "UnknownArgument"),
        Arguments.of(
            "{ lookUp(input: {id: \"1\", code: %s}) }".formatted(nested(CODE, 200)), "WrongType"));
  }

  // Each list holds the next, down to the code.
  private static String nested(String code, int depth) {
    return "[".repeat(depth) + "\"" + code + "\"" + "]".repeat(depth);
  }

  static Stream<String> unparsableDocuments() {
    return Stream.of(
        "{ lookUp(input: {id: \"1\", code: \"%s}) }".formatted(CODE),
        "{ lookUp(input: {id: \"1\", code: \"%s\\q\"}) }".formatted(CODE),
        "{ lookUp(input: {id: \"1\", code \"%s\"}) }".formatted(CODE),
        "{ lookUp(input: {id: \"1\", code: \"x\"}) } \"%s\"".formatted(CODE),
        "{ lookUp(input: {id: \"1\", code: \"x\"}) } %s".formatted(CODE.replace("-", "")));
  }

  static Stream<Arguments> invalidUnicodeEscapes() {
    return Stream.of(
        Arguments.of("\\u{48261379}", "48261379"),
        Arguments.of("\\uD800\\u4826", "4826"),
        Arguments.of("\\uD8BC", "D8BC"),
        Arguments.of("\\uDCBF", "DCBF"));
  }

  private GraphQLError singleError(ExecutionInput input) {
    var errors = graphQL.execute(input).getErrors();
    assertThat(errors).hasSize(1);
    return errors.getFirst();
  }

  // The memory one answer takes, once a first answer has loaded what answering needs.
  private static long allocatedAnswering(GraphQL engine, String document) {
    var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    engine.execute(document);
    var before = threads.getCurrentThreadAllocatedBytes();
    engine.execute(document);
    return threads.getCurrentThreadAllocatedBytes() - before;
  }

  // As the answer carries the error, and as a log line or a later instrumentation prints it.
  private static void assertSyntaxErrorAtItsLineAndColumnWithout(
      GraphQLError error, String... values) {
    var location = error.getLocations().getFirst();
    assertThat(error.getErrorType()).isEqualTo(ErrorType.InvalidSyntax);
    assertThat(error.getMessage())
        .contains("line %d column %d".formatted(location.getLine(), location.getColumn()));
    assertThat(error.toSpecification().toString()).doesNotContain(values);
    assertThat(error.toString()).doesNotContain(values);
  }
}
