package com.streamarr.server.fakes;

import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfilePolicyTransition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;

/**
 * Records the intents callers submit and answers them from result-family-specific rules. Unit
 * intents are allowed by default; Profile policy decisions must be configured explicitly. It never
 * exposes Cedar actions, entities, or policy ids — only the public {@link Decision} contract.
 */
public final class FakeAuthorizationService implements AuthorizationService {

  private final Supplier<AuthenticatedIdentity> identity;
  private final String tokenValue;
  private final List<Intent> intents = new ArrayList<>();
  private Function<Intent.UnitIntent, Decision<AuthorizationUnit>> unitRule = _ -> allowedUnit();
  private Function<UUID, Decision<AuthorizationUnit>> storedProposalRule = _ -> null;
  private Function<Intent.ProfilePolicyChange, Decision<ProfilePolicyTransition>> policyRule =
      _ -> unavailablePolicy();

  public FakeAuthorizationService(AuthenticatedIdentity identity) {
    this(() -> identity);
  }

  public FakeAuthorizationService(Supplier<AuthenticatedIdentity> identity) {
    this(identity, "test-token");
  }

  public FakeAuthorizationService(Supplier<AuthenticatedIdentity> identity, String tokenValue) {
    this.identity = identity;
    this.tokenValue = tokenValue;
  }

  /** Every intent is denied by policy until reconfigured. */
  public FakeAuthorizationService denyAll() {
    unitRule = _ -> new Decision.Denied<>(Decision.DenialReason.POLICY);
    policyRule = _ -> new Decision.Denied<>(Decision.DenialReason.POLICY);
    return this;
  }

  public FakeAuthorizationService allowAll() {
    unitRule = _ -> allowedUnit();
    policyRule = _ -> unavailablePolicy();
    return this;
  }

  /** Every intent fails closed with the given cause. */
  public FakeAuthorizationService failWith(Decision.FailureCause cause) {
    unitRule = _ -> new Decision.Failed<>(cause);
    policyRule = _ -> new Decision.Failed<>(cause);
    return this;
  }

  public FakeAuthorizationService decideUnitWith(
      Function<Intent.UnitIntent, Decision<AuthorizationUnit>> decisionRule) {
    unitRule = decisionRule;
    return this;
  }

  public FakeAuthorizationService decidePolicyWith(
      Function<Intent.ProfilePolicyChange, Decision<ProfilePolicyTransition>> decisionRule) {
    policyRule = decisionRule;
    return this;
  }

  public List<Intent> recordedIntents() {
    return List.copyOf(intents);
  }

  @Override
  public AuthenticatedIdentity currentIdentity() {
    return identity.get();
  }

  @Override
  public String currentTokenValue() {
    return tokenValue;
  }

  @Override
  public Instant currentTokenExpiry() {
    return Instant.now().plusSeconds(3600);
  }

  @Override
  public UUID requireAccountId() {
    return currentIdentity().accountId();
  }

  @Override
  public UUID requireHousehold() {
    return currentIdentity().contextHouseholdId();
  }

  @Override
  public UUID requireProfile() {
    return currentIdentity().profileId();
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

  /** Answers stored-proposal re-decisions for the given offerer instead of the unit rule. */
  public FakeAuthorizationService decideForAccountWith(
      Function<UUID, Decision<AuthorizationUnit>> rule) {
    storedProposalRule = rule;
    return this;
  }

  @Override
  public Decision<AuthorizationUnit> decideForAccount(UUID accountId, Intent.UnitIntent intent) {
    intents.add(intent);
    var stored = storedProposalRule.apply(accountId);
    if (stored != null) {
      return stored;
    }

    return unitRule.apply(intent);
  }

  @Override
  public AuthorizationUnit requireAllowed(
      AuthenticatedIdentity identity, Intent.UnitIntent intent) {
    return switch (decide(identity, intent)) {
      case Decision.Allowed<AuthorizationUnit>(var value) -> value;
      case Decision.Denied<AuthorizationUnit> _ -> throw new AccessDeniedException("Not allowed.");
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
    };
  }

  private static Decision<AuthorizationUnit> allowedUnit() {
    return new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  }

  private static Decision<ProfilePolicyTransition> unavailablePolicy() {
    return new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE);
  }
}
