package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.model.Context;
import com.cedarpolicy.value.EntityUID;

/** One Cedar question: action, resource, and the trusted attempt-specific context. */
record AuthorizationCheck(Action action, EntityUID resource, Context context) {

  static AuthorizationCheck onServer(Action action) {
    return new AuthorizationCheck(action, CedarIds.server(), new Context());
  }
}
