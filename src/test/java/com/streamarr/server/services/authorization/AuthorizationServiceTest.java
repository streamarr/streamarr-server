package com.streamarr.server.services.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("UnitTest")
@DisplayName("Authorization Service Tests")
class AuthorizationServiceTest {

  private final FakeAuthorizationDecider decider = new FakeAuthorizationDecider();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final AuthorizationService authorizationService =
      new SecurityContextAuthorizationService(decider, accounts);

  private final UUID accountId = UUID.randomUUID();
  private final UUID householdId = UUID.randomUUID();
  private final UUID visitedHouseholdId = UUID.randomUUID();
  private final UUID profileId = UUID.randomUUID();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should expose account id when authenticated")
  void shouldExposeAccountIdWhenAuthenticated() {
    authenticateWith(profileScopedIdentity());

    assertThat(authorizationService.requireAccountId()).isEqualTo(accountId);
  }

  @Test
  @DisplayName("Should expose the context Household when any scope is authenticated")
  void shouldExposeContextHouseholdWhenAnyScopeIsAuthenticated() {
    authenticateWith(profileScopedIdentity());
    assertThat(authorizationService.requireHousehold()).isEqualTo(visitedHouseholdId);

    authenticateWith(accountScopedIdentity());
    assertThat(authorizationService.requireHousehold()).isEqualTo(householdId);

    authenticateWith(playbackScopedIdentity());
    assertThat(authorizationService.requireHousehold()).isEqualTo(visitedHouseholdId);
  }

  @Test
  @DisplayName("Should expose profile id when profile scoped")
  void shouldExposeProfileIdWhenProfileScoped() {
    authenticateWith(profileScopedIdentity());

    assertThat(authorizationService.requireProfile()).isEqualTo(profileId);
  }

  @Test
  @DisplayName("Should require profile when scope has no profile")
  void shouldRequireProfileWhenScopeHasNoProfile() {
    authenticateWith(accountScopedIdentity());

    assertThatThrownBy(authorizationService::requireProfile)
        .isInstanceOf(ProfileRequiredException.class);
  }

  @Test
  @DisplayName("Should return the decider's decision when an intent is decided")
  void shouldReturnDecidersDecisionWhenIntentIsDecided() {
    var identity = profileScopedIdentity();
    decider.denyAll();

    var decision = authorizationService.decide(identity, new Intent.AddLibrary());

    assertThat(decision).isEqualTo(new Decision.Denied<>(Decision.DenialReason.POLICY));
    assertThat(decider.recordedIntents()).containsExactly(new Intent.AddLibrary());
  }

  @Test
  @DisplayName("Should use the Account's current relationships when a stored proposal is decided")
  void shouldUseAccountsCurrentRelationshipsWhenStoredProposalIsDecided() {
    var account =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(householdId)
                .householdRole(HouseholdRole.MEMBER)
                .serverAdmin(true)
                .build());
    var currentRelationships = new CurrentRelationshipDecider(householdId);
    var liveAuthorizationService =
        new SecurityContextAuthorizationService(currentRelationships, accounts);
    var intent = new Intent.OfferProfileShare(UUID.randomUUID());

    var currentDecision = liveAuthorizationService.decideForAccount(account.getId(), intent);

    assertThat(currentDecision).isEqualTo(new Decision.Allowed<>(AuthorizationUnit.INSTANCE));

    account.setServerAdmin(false);
    accounts.save(account);

    var changedDecision = liveAuthorizationService.decideForAccount(account.getId(), intent);

    assertThat(changedDecision).isEqualTo(new Decision.Denied<>(Decision.DenialReason.POLICY));
  }

  @Test
  @DisplayName("Should deny a stored proposal when its Account no longer exists")
  void shouldDenyStoredProposalWhenItsAccountNoLongerExists() {
    var decision =
        authorizationService.decideForAccount(
            UUID.randomUUID(), new Intent.OfferProfileShare(UUID.randomUUID()));

    assertThat(decision).isEqualTo(new Decision.Denied<>(Decision.DenialReason.POLICY));
    assertThat(decider.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should return the allowed value when a whole-surface gate is allowed")
  void shouldReturnAllowedValueWhenWholeSurfaceGateIsAllowed() {
    var value =
        authorizationService.requireAllowed(profileScopedIdentity(), new Intent.AddLibrary());

    assertThat(value).isEqualTo(AuthorizationUnit.INSTANCE);
  }

  @Test
  @DisplayName("Should throw access denied when a whole-surface gate is denied")
  void shouldThrowAccessDeniedWhenWholeSurfaceGateIsDenied() {
    var identity = profileScopedIdentity();
    var intent = new Intent.AddLibrary();
    decider.denyAll();

    assertThatThrownBy(() -> authorizationService.requireAllowed(identity, intent))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should throw authorization unavailable when no decision could be made")
  void shouldThrowAuthorizationUnavailableWhenNoDecisionCouldBeMade() {
    var identity = profileScopedIdentity();
    var intent = new Intent.AddLibrary();
    decider.failWith(Decision.FailureCause.ENGINE_FAILURE);

    assertThatThrownBy(() -> authorizationService.requireAllowed(identity, intent))
        .isInstanceOf(AuthorizationUnavailableException.class)
        .hasMessage("Authorization is temporarily unavailable.");
  }

  @Test
  @DisplayName("Should reject identity when unauthenticated")
  void shouldRejectIdentityWhenUnauthenticated() {
    assertThatThrownBy(authorizationService::currentIdentity)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should reject the token value when unauthenticated")
  void shouldRejectCurrentTokenValueWhenUnauthenticated() {
    assertThatThrownBy(authorizationService::currentTokenValue)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should return the validated token value when authenticated with a JWT credential")
  void shouldReturnValidatedTokenValueWhenAuthenticated() {
    authenticateWith(profileScopedIdentity(), jwt("signed.jwt.value", null));

    assertThat(authorizationService.currentTokenValue()).isEqualTo("signed.jwt.value");
  }

  @Test
  @DisplayName("Should return the validated token expiry when the JWT carries one")
  void shouldReturnValidatedTokenExpiryWhenJwtCarriesOne() {
    var expiresAt = Instant.parse("2026-01-01T00:10:00Z");
    authenticateWith(profileScopedIdentity(), jwt("signed.jwt.value", expiresAt));

    assertThat(authorizationService.currentTokenExpiry()).isEqualTo(expiresAt);
  }

  @Test
  @DisplayName("Should reject the token expiry when unauthenticated or the JWT has no expiry")
  void shouldRejectTokenExpiryWhenUnauthenticatedOrJwtHasNoExpiry() {
    assertThatThrownBy(authorizationService::currentTokenExpiry)
        .isInstanceOf(AuthenticationRequiredException.class);

    authenticateWith(profileScopedIdentity());
    assertThatThrownBy(authorizationService::currentTokenExpiry)
        .isInstanceOf(AuthenticationRequiredException.class);

    authenticateWith(profileScopedIdentity(), jwt("signed.jwt.value", null));
    assertThatThrownBy(authorizationService::currentTokenExpiry)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should reject the token value when our token carries no JWT credential")
  void shouldRejectTokenValueWhenStreamarrTokenCarriesNoJwt() {
    authenticateWith(profileScopedIdentity());

    assertThatThrownBy(authorizationService::currentTokenValue)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should refuse the facade when a foreign authentication type carries a JWT")
  void shouldRefuseFacadeWhenForeignAuthenticationCarriesJwt() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "principal",
                jwt("attacker.controlled.jwt", null),
                List.of(new SimpleGrantedAuthority(TokenScope.PROFILE.authority()))));

    assertThatThrownBy(authorizationService::currentIdentity)
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(authorizationService::currentTokenValue)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  private AuthenticatedIdentity profileScopedIdentity() {
    return AuthenticatedIdentity.builder()
        .accountId(accountId)
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.PROFILE)
        .householdId(householdId)
        .householdRole(HouseholdRole.MEMBER)
        .contextHouseholdId(visitedHouseholdId)
        .profileId(profileId)
        .build();
  }

  private AuthenticatedIdentity accountScopedIdentity() {
    return AuthenticatedIdentity.builder()
        .accountId(accountId)
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.ACCOUNT)
        .householdId(householdId)
        .householdRole(HouseholdRole.MEMBER)
        .contextHouseholdId(householdId)
        .build();
  }

  private AuthenticatedIdentity playbackScopedIdentity() {
    return AuthenticatedIdentity.builder()
        .accountId(accountId)
        .authSessionId(UUID.randomUUID())
        .scope(TokenScope.PLAYBACK)
        .householdId(householdId)
        .householdRole(HouseholdRole.MEMBER)
        .contextHouseholdId(visitedHouseholdId)
        .profileId(profileId)
        .streamSessionId(UUID.randomUUID())
        .build();
  }

  private Jwt jwt(String value, Instant expiresAt) {
    var builder = Jwt.withTokenValue(value).header("typ", "JWT").subject(accountId.toString());
    if (expiresAt != null) {
      builder.expiresAt(expiresAt);
    }

    return builder.build();
  }

  private void authenticateWith(AuthenticatedIdentity identity) {
    authenticateWith(identity, null);
  }

  private void authenticateWith(AuthenticatedIdentity identity, Jwt token) {
    var authorities = List.of(new SimpleGrantedAuthority(identity.scope().authority()));
    SecurityContextHolder.getContext()
        .setAuthentication(new StreamarrAuthenticationToken(identity, token, authorities));
  }

  private record CurrentRelationshipDecider(UUID expectedHouseholdId)
      implements AuthorizationDecider {

    @Override
    @SuppressWarnings("unchecked")
    public <T> Decision<T> decide(AuthenticatedIdentity identity, Intent<T> intent) {
      var hasCurrentRelationships =
          expectedHouseholdId.equals(identity.householdId())
              && identity.householdRole() == HouseholdRole.MEMBER
              && identity.serverAdmin();
      if (hasCurrentRelationships) {
        return (Decision<T>) new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
      }
      return new Decision.Denied<>(Decision.DenialReason.POLICY);
    }
  }
}
