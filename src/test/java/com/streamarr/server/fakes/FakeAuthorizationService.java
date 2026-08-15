package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
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

  /**
   * Retrieves the identifier of the current profile.
   *
   * @return the current profile identifier
   */
  @Override
  public UUID requireProfile() {
    return currentIdentity().profileId();
  }

  /**
   * Creates playback authority for the current identity.
   *
   * @return playback authority containing the current session, account, household, and profile identifiers
   */
  @Override
  public PlaybackAuthority requirePlaybackAuthority() {
    var current = currentIdentity();
    return PlaybackAuthority.builder()
        .authSessionId(current.authSessionId())
        .accountId(current.accountId())
        .householdId(current.householdId())
        .profileId(current.profileId())
        .build();
  }

  /**
   * Determines whether the current identity has server administrator privileges.
   *
   * @return {@code true} if the current identity has the {@code ADMIN} account role, {@code false} otherwise
   */
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

  /**
   * Leaves household authorization decisions to the test configuration.
   *
   * @param minimum the minimum household role required
   */
  @Override
  public void requireHouseholdRole(HouseholdRole minimum) {
    // Authorization decisions are configured by the test using this fake.
  }

  /**
   * Determines whether activity can be viewed for a profile.
   *
   * @param profileId the profile whose activity access is evaluated
   * @return {@code true} for any profile
   */
  @Override
  public boolean canViewActivityOf(UUID profileId) {
    return true;
  }
}
