package com.streamarr.server.repositories;

import static com.streamarr.server.repositories.CancellableDatabaseWork.CURRENT_CALL;

import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.springframework.stereotype.Component;

// Tells the CancellableDatabaseWork call that runs on this thread which statement it runs, from
// just before the statement reaches its socket until jOOQ has read its results and closed it.
@Component
class CancellableStatementListener implements ExecuteListenerProvider, ExecuteListener {

  @Override
  public ExecuteListener provide() {
    return this;
  }

  @Override
  public void executeStart(ExecuteContext context) {
    if (!CURRENT_CALL.isBound()) {
      return;
    }

    CURRENT_CALL.get().startStatement(context.statement());
  }

  @Override
  public void end(ExecuteContext context) {
    if (!CURRENT_CALL.isBound()) {
      return;
    }

    CURRENT_CALL.get().endStatement();
  }
}
