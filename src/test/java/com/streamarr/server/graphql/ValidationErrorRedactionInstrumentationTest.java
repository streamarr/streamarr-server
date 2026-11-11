package com.streamarr.server.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Validation Error Redaction Instrumentation Tests")
class ValidationErrorRedactionInstrumentationTest {

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

  private final GraphQL graphQL =
      GraphQL.newGraphQL(
              new SchemaGenerator()
                  .makeExecutableSchema(
                      new SchemaParser().parse(SCHEMA), RuntimeWiring.newRuntimeWiring().build()))
          .instrumentation(new ValidationErrorRedactionInstrumentation())
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

  private GraphQLError singleError(ExecutionInput input) {
    var errors = graphQL.execute(input).getErrors();
    assertThat(errors).hasSize(1);
    return errors.getFirst();
  }
}
