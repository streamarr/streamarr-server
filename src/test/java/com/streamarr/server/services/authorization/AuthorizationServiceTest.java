package com.streamarr.server.services.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("UnitTest")
@DisplayName("Authorization Service Tests")
class AuthorizationServiceTest {

  private final AuthorizationService authorizationService =
      new SecurityContextAuthorizationService();

  private final UUID accountId = UUID.randomUUID();
  private final UUID householdId = UUID.randomUUID();
  private final UUID profileId = UUID.randomUUID();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should expose identity ids when profile scoped")
  void shouldExposeIdentityIdsWhenProfileScoped() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));

    assertThat(authorizationService.requireAccountId()).isEqualTo(accountId);
    assertThat(authorizationService.requireHousehold()).isEqualTo(householdId);
    assertThat(authorizationService.requireProfile()).isEqualTo(profileId);
    assertThat(authorizationService.isServerAdmin()).isFalse();
  }

  @Test
  @DisplayName("Should require selection when scope too narrow")
  void shouldRequireSelectionWhenScopeTooNarrow() {
    authenticateWith(
        AuthenticatedIdentity.builder()
            .accountId(accountId)
            .role(AccountRole.USER)
            .sessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .build());

    assertThatThrownBy(authorizationService::requireHousehold)
        .isInstanceOf(HouseholdRequiredException.class);
    assertThatThrownBy(authorizationService::requireProfile)
        .isInstanceOf(ProfileRequiredException.class);
    assertThatThrownBy(() -> authorizationService.requireHouseholdRole(HouseholdRole.MEMBER))
        .isInstanceOf(HouseholdRequiredException.class);
    assertThat(authorizationService.canViewActivityOf(UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName("Should reject when unauthenticated")
  void shouldRejectWhenUnauthenticated() {
    assertThatThrownBy(authorizationService::requireAccountId)
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(authorizationService::currentIdentity)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should enforce household role minimums")
  void shouldEnforceHouseholdRoleMinimums() {
    authenticateWith(profileScopedIdentity(HouseholdRole.PARENT, AccountRole.USER));

    authorizationService.requireHouseholdRole(HouseholdRole.MEMBER);
    authorizationService.requireHouseholdRole(HouseholdRole.PARENT);
    assertThatThrownBy(() -> authorizationService.requireHouseholdRole(HouseholdRole.OWNER))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should gate server admin checks on account role")
  void shouldGateServerAdminChecksOnAccountRole() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.ADMIN));

    assertThat(authorizationService.isServerAdmin()).isTrue();
    authorizationService.requireServerAdmin();

    authenticateWith(profileScopedIdentity(HouseholdRole.OWNER, AccountRole.USER));
    assertThatThrownBy(authorizationService::requireServerAdmin)
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should allow viewing own activity and parents viewing others")
  void shouldAllowViewingOwnActivityAndParentsViewingOthers() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));
    assertThat(authorizationService.canViewActivityOf(profileId)).isTrue();
    assertThat(authorizationService.canViewActivityOf(UUID.randomUUID())).isFalse();

    authenticateWith(profileScopedIdentity(HouseholdRole.PARENT, AccountRole.USER));
    assertThat(authorizationService.canViewActivityOf(UUID.randomUUID())).isTrue();
  }

  private AuthenticatedIdentity profileScopedIdentity(
      HouseholdRole householdRole, AccountRole role) {
    return AuthenticatedIdentity.builder()
        .accountId(accountId)
        .role(role)
        .sessionId(UUID.randomUUID())
        .scope(TokenScope.PROFILE)
        .householdId(householdId)
        .householdRole(householdRole)
        .profileId(profileId)
        .build();
  }

  private void authenticateWith(AuthenticatedIdentity identity) {
    var authorities = List.of(new SimpleGrantedAuthority("SCOPE_" + identity.scope().name()));
    SecurityContextHolder.getContext()
        .setAuthentication(new StreamarrAuthenticationToken(identity, null, authorities));
  }
}
