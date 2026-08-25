package com.streamarr.server.fakes;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationDecider;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfilePolicyTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Stands in for the Cedar engine behind the facade with one rule per result family. */
public final class FakeAuthorizationDecider implements AuthorizationDecider {

  private final List<Intent> intents = new ArrayList<>();
  private Function<Intent.UnitIntent, Decision<AuthorizationUnit>> unitRule =
      _ -> new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  private Function<Intent.ProfilePolicyChange, Decision<ProfilePolicyTransition>> policyRule =
      _ -> new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE);

  public FakeAuthorizationDecider denyAll() {
    unitRule = _ -> new Decision.Denied<>(Decision.DenialReason.POLICY);
    policyRule = _ -> new Decision.Denied<>(Decision.DenialReason.POLICY);
    return this;
  }

  public FakeAuthorizationDecider allowAll() {
    unitRule = _ -> new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
    policyRule = _ -> new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE);
    return this;
  }

  public FakeAuthorizationDecider failWith(Decision.FailureCause cause) {
    unitRule = _ -> new Decision.Failed<>(cause);
    policyRule = _ -> new Decision.Failed<>(cause);
    return this;
  }

  public FakeAuthorizationDecider decideUnitWith(
      Function<Intent.UnitIntent, Decision<AuthorizationUnit>> decisionRule) {
    unitRule = decisionRule;
    return this;
  }

  public FakeAuthorizationDecider decidePolicyWith(
      Function<Intent.ProfilePolicyChange, Decision<ProfilePolicyTransition>> decisionRule) {
    policyRule = decisionRule;
    return this;
  }

  public List<Intent> recordedIntents() {
    return List.copyOf(intents);
  }

  @Override
  public Decision<AuthorizationUnit> decide(
      AuthenticatedIdentity identity, Intent.UnitIntent intent) {
    intents.add(intent);
    return unitRule.apply(intent);
  }

  @Override
  public Decision<ProfilePolicyTransition> decide(
      AuthenticatedIdentity identity, Intent.ProfilePolicyChange intent) {
    intents.add(intent);
    return policyRule.apply(intent);
  }
}
