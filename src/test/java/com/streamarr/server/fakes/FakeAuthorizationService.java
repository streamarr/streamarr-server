package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;

public final class FakeAuthorizationService implements AuthorizationService {

  private final Supplier<AuthenticatedIdentity> identity;
  private final String tokenValue;

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
  public boolean isServerAdmin() {
    return currentIdentity().role() == AccountRole.ADMIN;
  }

  @Override
  public void requireServerAdmin() {
    if (!isServerAdmin()) {
      throw new AccessDeniedException("Server administrator role is required.");
    }
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
}
