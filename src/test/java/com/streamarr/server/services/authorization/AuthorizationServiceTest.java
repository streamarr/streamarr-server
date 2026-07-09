package com.streamarr.server.services.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

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
  @DisplayName("Should expose account id when authenticated")
  void shouldExposeAccountIdWhenAuthenticated() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));

    assertThat(authorizationService.requireAccountId()).isEqualTo(accountId);
  }

  @Test
  @DisplayName("Should expose household id when profile scoped")
  void shouldExposeHouseholdIdWhenProfileScoped() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));

    assertThat(authorizationService.requireHousehold()).isEqualTo(householdId);
  }

  @Test
  @DisplayName("Should expose profile id when profile scoped")
  void shouldExposeProfileIdWhenProfileScoped() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));

    assertThat(authorizationService.requireProfile()).isEqualTo(profileId);
  }

  @Test
  @DisplayName("Should report non admin when account role user")
  void shouldReportNonAdminWhenAccountRoleUser() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));

    assertThat(authorizationService.isServerAdmin()).isFalse();
  }

  @Test
  @DisplayName("Should report server admin when account role admin")
  void shouldReportServerAdminWhenAccountRoleAdmin() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.ADMIN));

    assertThat(authorizationService.isServerAdmin()).isTrue();
  }

  @Test
  @DisplayName("Should require household when scope has no household")
  void shouldRequireHouseholdWhenScopeHasNoHousehold() {
    authenticateWith(accountScopedIdentity());

    assertThatThrownBy(authorizationService::requireHousehold)
        .isInstanceOf(HouseholdRequiredException.class);
  }

  @Test
  @DisplayName("Should require profile when scope has no profile")
  void shouldRequireProfileWhenScopeHasNoProfile() {
    authenticateWith(accountScopedIdentity());

    assertThatThrownBy(authorizationService::requireProfile)
        .isInstanceOf(ProfileRequiredException.class);
  }

  @Test
  @DisplayName("Should require household role when scope has no household")
  void shouldRequireHouseholdRoleWhenScopeHasNoHousehold() {
    authenticateWith(accountScopedIdentity());

    assertThatThrownBy(() -> authorizationService.requireHouseholdRole(HouseholdRole.MEMBER))
        .isInstanceOf(HouseholdRequiredException.class);
  }

  @Test
  @DisplayName("Should deny viewing activity when scope has no household")
  void shouldDenyViewingActivityWhenScopeHasNoHousehold() {
    authenticateWith(accountScopedIdentity());

    assertThat(authorizationService.canViewActivityOf(UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName("Should reject account id when unauthenticated")
  void shouldRejectAccountIdWhenUnauthenticated() {
    assertThatThrownBy(authorizationService::requireAccountId)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should reject current identity when unauthenticated")
  void shouldRejectCurrentIdentityWhenUnauthenticated() {
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
    var jwt =
        Jwt.withTokenValue("signed.jwt.value")
            .header("typ", "JWT")
            .subject(accountId.toString())
            .build();
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER), jwt);

    assertThat(authorizationService.currentTokenValue()).isEqualTo("signed.jwt.value");
  }

  @Test
  @DisplayName("Should reject the token value when our token carries no JWT credential")
  void shouldRejectTokenValueWhenStreamarrTokenCarriesNoJwt() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));

    assertThatThrownBy(authorizationService::currentTokenValue)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should refuse the facade when a foreign authentication type carries a JWT")
  void shouldRefuseFacadeWhenForeignAuthenticationCarriesJwt() {
    var jwt =
        Jwt.withTokenValue("attacker.controlled.jwt")
            .header("typ", "JWT")
            .subject(UUID.randomUUID().toString())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "principal",
                jwt,
                List.of(new SimpleGrantedAuthority(TokenScope.PROFILE.authority()))));

    assertThatThrownBy(authorizationService::currentIdentity)
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(authorizationService::currentTokenValue)
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName(
      "Should deny viewing a same-household profile when the household claim carries no role")
  void shouldDenyViewingSameHouseholdProfileWhenHouseholdClaimHasNoRole() {
    var sameHousehold = saveProfile(householdId);
    authenticateWith(
        AuthenticatedIdentity.builder()
            .accountId(accountId)
            .role(AccountRole.USER)
            .sessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .householdId(householdId)
            .profileId(profileId)
            .build());

    assertThat(authorizationService.canViewActivityOf(sameHousehold.getId())).isFalse();
  }

  @ParameterizedTest(name = "Should allow household role when minimum is {0}")
  @EnumSource(
      value = HouseholdRole.class,
      names = {"MEMBER", "PARENT"})
  void shouldAllowHouseholdRoleWhenRoleMeetsMinimum(HouseholdRole minimum) {
    authenticateWith(profileScopedIdentity(HouseholdRole.PARENT, AccountRole.USER));

    assertThatCode(() -> authorizationService.requireHouseholdRole(minimum))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should deny household role when minimum above role")
  void shouldDenyHouseholdRoleWhenMinimumAboveRole() {
    authenticateWith(profileScopedIdentity(HouseholdRole.PARENT, AccountRole.USER));

    assertThatThrownBy(() -> authorizationService.requireHouseholdRole(HouseholdRole.OWNER))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should allow server admin requirement when account role admin")
  void shouldAllowServerAdminRequirementWhenAccountRoleAdmin() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.ADMIN));

    assertThatCode(authorizationService::requireServerAdmin).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should deny server admin requirement when account role user")
  void shouldDenyServerAdminRequirementWhenAccountRoleUser() {
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
  @DisplayName("Should allow a parent to view activity when the profile is in the active household")
  void shouldAllowParentToViewActivityWhenProfileInActiveHousehold() {
    var managed = saveProfile(householdId);
    authenticateWith(profileScopedIdentity(HouseholdRole.PARENT, AccountRole.USER));

    assertThat(authorizationService.canViewActivityOf(managed.getId())).isTrue();
  }

  @Test
  @DisplayName(
      "Should deny a parent from viewing activity when the profile is outside the active household")
  void shouldDenyParentFromViewingActivityWhenProfileOutsideActiveHousehold() {
    var foreign = saveProfile(UUID.randomUUID());
    authenticateWith(profileScopedIdentity(HouseholdRole.PARENT, AccountRole.USER));

    assertThat(authorizationService.canViewActivityOf(foreign.getId())).isFalse();
  }

  @Test
  @DisplayName("Should deny parent viewing activity when the profile is missing")
  void shouldDenyParentViewingActivityWhenProfileMissing() {
    authenticateWith(profileScopedIdentity(HouseholdRole.PARENT, AccountRole.USER));

    assertThat(authorizationService.canViewActivityOf(UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName("Should deny viewing activity when target profile id missing")
  void shouldDenyViewingActivityWhenTargetProfileIdMissing() {
    authenticateWith(profileScopedIdentity(HouseholdRole.MEMBER, AccountRole.USER));

    assertThat(authorizationService.canViewActivityOf(null)).isFalse();
  }

  @Test
  @DisplayName("Should allow viewing any profile activity when the caller is a server admin")
  void shouldAllowViewingAnyProfileActivityWhenServerAdmin() {
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

  private AuthenticatedIdentity accountScopedIdentity() {
    return AuthenticatedIdentity.builder()
        .accountId(accountId)
        .role(AccountRole.USER)
        .sessionId(UUID.randomUUID())
        .scope(TokenScope.ACCOUNT)
        .build();
  }

  private void authenticateWith(AuthenticatedIdentity identity) {
    authenticateWith(identity, null);
  }

  private void authenticateWith(AuthenticatedIdentity identity, Jwt token) {
    var authorities = List.of(new SimpleGrantedAuthority(identity.scope().authority()));
    SecurityContextHolder.getContext()
        .setAuthentication(new StreamarrAuthenticationToken(identity, token, authorities));
  }
}
