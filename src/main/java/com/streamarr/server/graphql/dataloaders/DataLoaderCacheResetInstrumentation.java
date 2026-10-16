package com.streamarr.server.graphql.dataloaders;

import graphql.ExecutionResult;
import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters;
import graphql.language.OperationDefinition.Operation;
import org.dataloader.DataLoader;
import org.springframework.stereotype.Component;

/**
 * Empties every DataLoader cache before each root mutation field and before each subscription
 * event. GraphQL runs the root fields of a mutation one after another in one request, and the
 * request shares one DataLoader cache. Without this reset, a payload field would read a value that
 * an earlier mutation field loaded before the current field changed the data. A subscription keeps
 * one DataLoader cache for as long as it stays open, so without the reset each event would repeat
 * the values the first event loaded. Queries keep their cache.
 */
@Component
class DataLoaderCacheResetInstrumentation extends SimplePerformantInstrumentation {

  @Override
  public InstrumentationContext<Object> beginFieldExecution(
      InstrumentationFieldParameters parameters, InstrumentationState state) {
    var executionContext = parameters.getExecutionContext();
    var isMutation = executionContext.getOperationDefinition().getOperation() == Operation.MUTATION;
    var isRootField = parameters.getExecutionStepInfo().getPath().getLevel() == 1;
    if (isMutation && isRootField) {
      clearDataLoaderCaches(executionContext);
    }

    return super.beginFieldExecution(parameters, state);
  }

  @Override
  public InstrumentationContext<ExecutionResult> beginSubscribedFieldEvent(
      InstrumentationFieldParameters parameters, InstrumentationState state) {
    clearDataLoaderCaches(parameters.getExecutionContext());
    return super.beginSubscribedFieldEvent(parameters, state);
  }

  private static void clearDataLoaderCaches(ExecutionContext executionContext) {
    executionContext.getDataLoaderRegistry().getDataLoaders().forEach(DataLoader::clearAll);
  }
}
