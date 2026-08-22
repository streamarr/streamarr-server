package com.streamarr.server.fakes;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationDecider;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Stands in for the Cedar engine behind the facade: allows everything until told otherwise. */
public final class FakeAuthorizationDecider implements AuthorizationDecider {

  private final List<Intent<?>> intents = new ArrayList<>();
  private Function<Intent<?>, Decision<?>> rule =
      _ -> new Decision.Allowed<>(AuthorizationUnit.INSTANCE);

  public FakeAuthorizationDecider denyAll() {
    rule = _ -> new Decision.Denied<>(Decision.DenialReason.POLICY);
    return this;
  }

  public FakeAuthorizationDecider allowAll() {
    rule = _ -> new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
    return this;
  }

  public FakeAuthorizationDecider failWith(Decision.FailureCause cause) {
    rule = _ -> new Decision.Failed<>(cause);
    return this;
  }

  public List<Intent<?>> recordedIntents() {
    return List.copyOf(intents);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Decision<T> decide(AuthenticatedIdentity identity, Intent<T> intent) {
    intents.add(intent);
    return (Decision<T>) rule.apply(intent);
  }
}
