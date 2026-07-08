package com.streamarr.server.services.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAccountProfileRepository;
import com.streamarr.server.fakes.FakeHouseholdMembershipRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fixtures.ProfileFixture;
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

  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeAccountProfileRepository accountProfileRepository =
      new FakeAccountProfileRepository(new FakeHouseholdMembershipRepository());

  private final AuthorizationService authorizationService =
      new SecurityContextAuthorizationService(profileRepository, accountProfileRepository);

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
  @DisplayName("Should allow viewing activity when target is own active profile")
  void shouldAllowViewingActivityWhenTargetIsOwnActiveProfile() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));

    assertThat(authorizationService.canViewActivityOf(profileId)).isTrue();
  }

  @Test
  @DisplayName("Should allow viewing activity when member granted target profile")
  void shouldAllowViewingActivityWhenMemberGrantedTargetProfile() {
    var granted = saveProfile(householdId);
    accountProfileRepository.save(
        AccountProfile.builder()
            .accountId(accountId)
            .householdId(householdId)
            .profileId(granted.getId())
            .build());
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));

    assertThat(authorizationService.canViewActivityOf(granted.getId())).isTrue();
  }

  @Test
  @DisplayName("Should deny viewing activity when member not granted target profile")
  void shouldDenyViewingActivityWhenMemberNotGrantedTargetProfile() {
    var ungranted = saveProfile(householdId);
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));

    assertThat(authorizationService.canViewActivityOf(ungranted.getId())).isFalse();
  }

  @Test
  @DisplayName("Should allow parent viewing activity of profile in active household")
  void shouldAllowParentViewingActivityOfProfileInActiveHousehold() {
    var managed = saveProfile(householdId);
    authenticateWith(profileScopedIdentity(HouseholdRole.PARENT, AccountRole.USER));

    assertThat(authorizationService.canViewActivityOf(managed.getId())).isTrue();
  }

  @Test
  @DisplayName("Should deny parent viewing activity of profile outside active household")
  void shouldDenyParentViewingActivityOfProfileOutsideActiveHousehold() {
    var foreign = saveProfile(UUID.randomUUID());
    authenticateWith(profileScopedIdentity(HouseholdRole.PARENT, AccountRole.USER));

    assertThat(authorizationService.canViewActivityOf(foreign.getId())).isFalse();
    assertThat(authorizationService.canViewActivityOf(UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName("Should allow server admin viewing any profile activity")
  void shouldAllowServerAdminViewingAnyProfileActivity() {
    var foreign = saveProfile(UUID.randomUUID());
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.ADMIN));

    assertThat(authorizationService.canViewActivityOf(foreign.getId())).isTrue();
  }

  private Profile saveProfile(UUID owningHouseholdId) {
    var profile = ProfileFixture.defaultProfileBuilder().householdId(owningHouseholdId).build();
    profile.setId(UUID.randomUUID());
    return profileRepository.save(profile);
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
