package com.streamarr.server.graphql.transport;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.execution.AbortExecutionException;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.language.OperationDefinition.Operation;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Subscriptions run only over the WebSocket; queries and mutations run only over HTTP. A refused
 * operation ends before any data fetcher runs, so it registers nothing and changes nothing. Queries
 * and mutations sent over the socket would skip the HTTP CSRF filter and keep using the authority
 * captured at the handshake.
 */
@Component
public class OperationTransportInstrumentation extends SimplePerformantInstrumentation {

  @Override
  public InstrumentationContext<ExecutionResult> beginExecuteOperation(
      InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
    var executionContext = parameters.getExecutionContext();
    GraphQlTransport transport = executionContext.getGraphQLContext().get(GraphQlTransport.class);
    var refusal = refusal(executionContext.getOperationDefinition().getOperation(), transport);
    if (refusal.isPresent()) {
      throw new AbortExecutionException(List.of(refusal.get()));
    }

    return super.beginExecuteOperation(parameters, state);
  }

  // Only the HTTP and WebSocket handlers record a transport; in-process execution has none.
  private static Optional<GraphQLError> refusal(Operation operation, GraphQlTransport transport) {
    var subscription = operation == Operation.SUBSCRIPTION;
    if (transport == GraphQlTransport.HTTP && subscription) {
      return Optional.of(TransportRefusals.websocketRequired());
    }

    if (transport == GraphQlTransport.WEBSOCKET && !subscription) {
      return Optional.of(TransportRefusals.httpRequired());
    }

    return Optional.empty();
  }
}
