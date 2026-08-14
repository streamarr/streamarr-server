package com.streamarr.server.services.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("UnitTest")
@DisplayName("Authorization Service Tests")
class AuthorizationServiceTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final RequestAuthorizationStateResolver stateResolver =
      new RequestAuthorizationStateResolver(
          accountRepository, sessionRepository, shareRepository, new MutexFactoryProvider());
  private final AuthorizationService authorizationService =
      new SecurityContextAuthorizationService(stateResolver, shareRepository);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should fail profile access closed when signed profile is no longer shared")
  void shouldFailProfileAccessClosedWhenSignedProfileIsNoLongerShared() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    authenticate(account, session, UUID.randomUUID());

    assertThat(authorizationService.requireHousehold()).isEqualTo(homeHouseholdId);
    assertThatThrownBy(authorizationService::requireProfile)
        .isInstanceOf(ProfileRequiredException.class);
  }

  @Test
  @DisplayName("Should build playback authority from live home and active share")
  void shouldBuildPlaybackAuthorityFromLiveHomeAndActiveShare() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    var profileId = UUID.randomUUID();
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(homeHouseholdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    authenticate(account, session, profileId);

    var authority = authorizationService.requirePlaybackAuthority();

    assertThat(authority.authSessionId()).isEqualTo(session.getId());
    assertThat(authority.householdId()).isEqualTo(homeHouseholdId);
    assertThat(authority.profileId()).isEqualTo(profileId);
  }

  @Test
  @DisplayName("Should expose authenticated identity and bearer token metadata")
  void shouldExposeAuthenticatedIdentityAndBearerTokenMetadata() {
    var account = saveAccount(UUID.randomUUID());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    var expiresAt = Instant.parse("2026-08-14T12:00:00Z");
    var jwt =
        Jwt.withTokenValue("signed-token")
            .header("alg", "none")
            .subject(account.getId().toString())
            .expiresAt(expiresAt)
            .build();
    var identity = identity(account, session, null);
    authenticate(identity, jwt);

    assertThat(authorizationService.currentIdentity()).isEqualTo(identity);
    assertThat(authorizationService.currentTokenValue()).isEqualTo("signed-token");
    assertThat(authorizationService.currentTokenExpiry()).isEqualTo(expiresAt);
    assertThat(authorizationService.requireAccountId()).isEqualTo(account.getId());
  }

  @Test
  @DisplayName("Should reject authorization operations without Streamarr authentication")
  void shouldRejectAuthorizationOperationsWithoutStreamarrAuthentication() {
    assertThatThrownBy(authorizationService::currentIdentity)
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(authorizationService::currentTokenValue)
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(authorizationService::requireAccountId)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should reject token expiry lookup when bearer token has no expiry")
  void shouldRejectTokenExpiryLookupWhenBearerTokenHasNoExpiry() {
    var account = saveAccount(UUID.randomUUID());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    var jwt =
        Jwt.withTokenValue("signed-token")
            .header("alg", "none")
            .subject(account.getId().toString())
            .build();
    authenticate(identity(account, session, null), jwt);

    assertThatThrownBy(authorizationService::currentTokenExpiry)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should require a selected profile for playback authority")
  void shouldRequireSelectedProfileForPlaybackAuthority() {
    var account = saveAccount(UUID.randomUUID());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    authenticate(account, session, null);

    assertThatThrownBy(authorizationService::requirePlaybackAuthority)
        .isInstanceOf(ProfileRequiredException.class);
    assertThatThrownBy(authorizationService::currentTokenValue)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should require server administrator role")
  void shouldRequireServerAdministratorRole() {
    var admin = saveAccount(UUID.randomUUID(), HouseholdRole.MEMBER, AccountRole.ADMIN);
    var adminSession =
        sessionRepository.save(AuthSession.builder().accountId(admin.getId()).build());
    authenticate(admin, adminSession, null);

    assertThat(authorizationService.isServerAdmin()).isTrue();
    authorizationService.requireServerAdmin();

    var user = saveAccount(UUID.randomUUID());
    var userSession = sessionRepository.save(AuthSession.builder().accountId(user.getId()).build());
    authenticate(user, userSession, null);

    assertThat(authorizationService.isServerAdmin()).isFalse();
    assertThatThrownBy(authorizationService::requireServerAdmin)
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should enforce household role hierarchy")
  void shouldEnforceHouseholdRoleHierarchy() {
    var owner = saveAccount(UUID.randomUUID(), HouseholdRole.OWNER, AccountRole.USER);
    var ownerSession =
        sessionRepository.save(AuthSession.builder().accountId(owner.getId()).build());
    authenticate(owner, ownerSession, null);
    authorizationService.requireHouseholdRole(HouseholdRole.OWNER);

    var parent = saveAccount(UUID.randomUUID(), HouseholdRole.PARENT, AccountRole.USER);
    var parentSession =
        sessionRepository.save(AuthSession.builder().accountId(parent.getId()).build());
    authenticate(parent, parentSession, null);
    authorizationService.requireHouseholdRole(HouseholdRole.MEMBER);

    var member = saveAccount(UUID.randomUUID());
    var memberSession =
        sessionRepository.save(AuthSession.builder().accountId(member.getId()).build());
    authenticate(member, memberSession, null);

    assertThatThrownBy(() -> authorizationService.requireHouseholdRole(HouseholdRole.PARENT))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should authorize activity by administrator active profile or household parent")
  void shouldAuthorizeActivityByAdministratorActiveProfileOrHouseholdParent() {
    assertThat(authorizationService.canViewActivityOf(null)).isFalse();

    var admin = saveAccount(UUID.randomUUID(), HouseholdRole.MEMBER, AccountRole.ADMIN);
    authenticate(
        admin,
        sessionRepository.save(AuthSession.builder().accountId(admin.getId()).build()),
        null);
    assertThat(authorizationService.canViewActivityOf(UUID.randomUUID())).isTrue();

    var householdId = UUID.randomUUID();
    var member = saveAccount(householdId);
    var activeProfileId = UUID.randomUUID();
    activateProfile(activeProfileId, householdId);
    authenticate(
        member,
        sessionRepository.save(AuthSession.builder().accountId(member.getId()).build()),
        activeProfileId);
    assertThat(authorizationService.canViewActivityOf(activeProfileId)).isTrue();

    var parent = saveAccount(householdId, HouseholdRole.PARENT, AccountRole.USER);
    authenticate(
        parent,
        sessionRepository.save(AuthSession.builder().accountId(parent.getId()).build()),
        null);
    assertThat(authorizationService.canViewActivityOf(activeProfileId)).isTrue();
    assertThat(authorizationService.canViewActivityOf(UUID.randomUUID())).isFalse();

    authenticate(
        member,
        sessionRepository.save(AuthSession.builder().accountId(member.getId()).build()),
        null);
    assertThat(authorizationService.canViewActivityOf(activeProfileId)).isFalse();
  }

  private UserAccount saveAccount(UUID homeHouseholdId) {
    return saveAccount(homeHouseholdId, HouseholdRole.MEMBER, AccountRole.USER);
  }

  private UserAccount saveAccount(
      UUID homeHouseholdId, HouseholdRole householdRole, AccountRole accountRole) {
    return accountRepository.save(
        UserAccount.builder()
            .email("viewer-" + UUID.randomUUID() + "@example.com")
            .displayName("Viewer")
            .passwordHash("{noop}not-a-real-hash")
            .accountRole(accountRole)
            .homeHouseholdId(homeHouseholdId)
            .householdRole(householdRole)
            .build());
  }

  private void activateProfile(UUID profileId, UUID householdId) {
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
  }

  private void authenticate(UserAccount account, AuthSession session, UUID profileId) {
    authenticate(identity(account, session, profileId), null);
  }

  private AuthenticatedIdentity identity(UserAccount account, AuthSession session, UUID profileId) {
    var builder =
        AuthenticatedIdentity.builder()
            .accountId(account.getId())
            .role(account.getAccountRole())
            .authSessionId(session.getId())
            .scope(profileId == null ? TokenScope.ACCOUNT : TokenScope.PROFILE);
    if (profileId != null) {
      builder.profileId(profileId);
    }
    return builder.build();
  }

  private void authenticate(AuthenticatedIdentity identity, Jwt jwt) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new StreamarrAuthenticationToken(
                identity, jwt, List.of(new SimpleGrantedAuthority(identity.scope().authority()))));
  }
}
