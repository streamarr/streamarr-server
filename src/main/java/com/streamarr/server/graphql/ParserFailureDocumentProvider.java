package com.streamarr.server.graphql;

import graphql.AssertException;
import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.GraphqlErrorBuilder;
import graphql.ParseAndValidate;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.execution.preparsed.PreparsedDocumentProvider;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Answers with a syntax error a document that graphql-java's parser fails on without raising one.
 * Some literals the lexer accepts make the parser throw: a braced unicode escape whose digits
 * overflow an int, after a leading surrogate (a {@link NumberFormatException} that quotes the
 * digits) or alone (an {@link AssertException} about a missing message key), and a default value
 * whose float exponent overflows. Thrown, the exception would end the request in a server error
 * that the servlet container logs with its message. The answer names no line or column: the parser
 * gives none when it throws.
 */
@Component
class ParserFailureDocumentProvider implements PreparsedDocumentProvider {

  private static final String UNPARSABLE = "Invalid syntax";

  @Override
  public CompletableFuture<PreparsedDocumentEntry> getDocumentAsync(
      ExecutionInput executionInput,
      Function<ExecutionInput, PreparsedDocumentEntry> parseAndValidateFunction) {
    try {
      return CompletableFuture.completedFuture(parseAndValidateFunction.apply(executionInput));
    } catch (NumberFormatException | AssertException failure) {
      if (!parserFailsOn(executionInput)) {
        throw failure;
      }

      return CompletableFuture.completedFuture(
          new PreparsedDocumentEntry(
              GraphqlErrorBuilder.newError()
                  .errorType(ErrorType.InvalidSyntax)
                  .message(UNPARSABLE)
                  .build()));
    }
  }

  // A document that parses passed the failure on from validation or an instrumentation.
  private static boolean parserFailsOn(ExecutionInput executionInput) {
    try {
      ParseAndValidate.parse(executionInput);
      return false;
    } catch (NumberFormatException | AssertException _) {
      return true;
    }
  }
}
