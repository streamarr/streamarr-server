package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;

/**
 * Records the intents callers submit and answers them from a configurable rule (allow everything by
 * default). It never exposes Cedar actions, entities, or policy ids — only the public {@link
 * Decision} contract.
 */
public final class FakeAuthorizationService implements AuthorizationService {

  private final Supplier<AuthenticatedIdentity> identity;
  private final String tokenValue;
  private final List<Intent<?>> intents = new ArrayList<>();
  private Function<Intent<?>, Decision<?>> rule = FakeAuthorizationService::allow;

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

  /** Every intent is denied by policy until {@link #allowAll()}. */
  public FakeAuthorizationService denyAll() {
    rule = _ -> new Decision.Denied<>(Decision.DenialReason.POLICY);
    return this;
  }

  public FakeAuthorizationService allowAll() {
    rule = FakeAuthorizationService::allow;
    return this;
  }

  /** Every intent fails closed with the given cause. */
  public FakeAuthorizationService failWith(Decision.FailureCause cause) {
    rule = _ -> new Decision.Failed<>(cause);
    return this;
  }

  public List<Intent<?>> recordedIntents() {
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
    return currentIdentity().householdId();
  }

  @Override
  public UUID requireProfile() {
    return currentIdentity().profileId();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Decision<T> decide(AuthenticatedIdentity identity, Intent<T> intent) {
    intents.add(intent);
    return (Decision<T>) rule.apply(intent);
  }

  @Override
  public <T> T requireAllowed(AuthenticatedIdentity identity, Intent<T> intent) {
    return switch (decide(identity, intent)) {
      case Decision.Allowed<T>(var value) -> value;
      case Decision.Denied<T> _ -> throw new AccessDeniedException("Not allowed.");
      case Decision.Failed<T> _ -> throw new AuthorizationUnavailableException();
    };
  }

  @Override
  public void requireHouseholdRole(HouseholdRole minimum) {
    if (currentIdentity().householdRole() == null) {
      throw new AccessDeniedException("Household role is required.");
    }
  }

  @Override
  public boolean canViewActivityOf(UUID profileId) {
    return true;
  }

  private static Decision<?> allow(Intent<?> intent) {
    return new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  }
}
