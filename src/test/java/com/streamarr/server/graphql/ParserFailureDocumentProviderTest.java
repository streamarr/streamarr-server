package com.streamarr.server.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.AssertException;
import graphql.ErrorType;
import graphql.GraphQL;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.validation.ValidationError;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Parser Failure Document Provider Tests")
class ParserFailureDocumentProviderTest {

  private static final String CODE = "BCDF-GHJK";

  @ParameterizedTest
  @MethodSource("literalsTheParserFailsOn")
  @DisplayName("Should answer with a syntax error without the literal when the parser fails on it")
  void shouldAnswerWithSyntaxErrorWithoutLiteralWhenParserFailsOnIt(
      String document, String literal) {
    var result = graphQL(SimplePerformantInstrumentation.INSTANCE).execute(document);

    assertThat(result.getErrors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getErrorType()).isEqualTo(ErrorType.InvalidSyntax);
              assertThat(error.toSpecification().toString()).doesNotContain(literal);
              assertThat(error.toString()).doesNotContain(literal);
            });
  }

  @Test
  @DisplayName("Should answer as before when the document parses")
  void shouldAnswerAsBeforeWhenDocumentParses() {
    var result =
        graphQL(SimplePerformantInstrumentation.INSTANCE)
            .execute("{ lookUp(code: \"%s\") }".formatted(CODE));

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.isDataPresent()).isTrue();
  }

  @Test
  @DisplayName("Should let the failure through when validation throws on a document that parses")
  void shouldLetFailureThroughWhenValidationThrowsOnDocumentThatParses() {
    var throwingValidation =
        new SimplePerformantInstrumentation() {
          @Override
          public InstrumentationContext<List<ValidationError>> beginValidation(
              InstrumentationValidationParameters parameters, InstrumentationState state) {
            throw new AssertException("Validation broke");
          }
        };
    var graphQL = graphQL(throwingValidation);
    var document = "{ lookUp(code: \"%s\") }".formatted(CODE);

    assertThatThrownBy(() -> graphQL.execute(document)).isInstanceOf(AssertException.class);
  }

  static Stream<Arguments> literalsTheParserFailsOn() {
    return Stream.of(
        Arguments.of("{ lookUp(code: \"\\uD800\\u{123456789}\") }", "123456789"),
        Arguments.of("{ lookUp(code: \"\\u{BCDFDCBF}\") }", "BCDFDCBF"),
        Arguments.of(
            "query ($ratio: Float = 1e99999999999) { lookUp(code: \"%s\", ratio: $ratio) }"
                .formatted(CODE),
            "99999999999"));
  }

  private static GraphQL graphQL(Instrumentation instrumentation) {
    var schema =
        new SchemaGenerator()
            .makeExecutableSchema(
                new SchemaParser()
                    .parse("type Query { lookUp(code: String!, ratio: Float): String }"),
                RuntimeWiring.newRuntimeWiring().build());
    return GraphQL.newGraphQL(schema)
        .preparsedDocumentProvider(new ParserFailureDocumentProvider())
        .instrumentation(instrumentation)
        .build();
  }
}
