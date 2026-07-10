package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.auth.TokenScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.DefaultAuthorizationManagerFactory;
import org.springframework.security.core.Authentication;

@Tag("UnitTest")
@DisplayName("Scope Hierarchy Tests")
class ScopeHierarchyTest {

  private final DefaultAuthorizationManagerFactory<Object> factory = buildFactory();

  @ParameterizedTest(name = "Should grant account scope when authority is {0}")
  @EnumSource(
      value = TokenScope.class,
      names = {"PROFILE", "HOUSEHOLD", "ACCOUNT"})
  void shouldGrantAccountScopeWhenAuthorityAtOrAboveAccount(TokenScope scope) {
    var accountCheck = factory.hasAuthority(TokenScope.ACCOUNT.authority());

    assertThat(accountCheck.authorize(() -> authWith(scope.authority()), new Object()).isGranted())
        .isTrue();
  }

  @Test
  @DisplayName("Should keep playback scope outside API hierarchy")
  void shouldKeepPlaybackScopeOutsideApiHierarchy() {
    var accountCheck = factory.hasAuthority(TokenScope.ACCOUNT.authority());

    assertThat(
            accountCheck
                .authorize(() -> authWith(TokenScope.PLAYBACK.authority()), new Object())
                .isGranted())
        .isFalse();
  }

  @ParameterizedTest(name = "Should deny playback scope when authority is {0}")
  @EnumSource(
      value = TokenScope.class,
      names = {"ACCOUNT", "HOUSEHOLD", "PROFILE"})
  void shouldDenyPlaybackScopeWhenAuthorityIsApiScoped(TokenScope scope) {
    var playbackCheck = factory.hasAuthority(TokenScope.PLAYBACK.authority());

    assertThat(playbackCheck.authorize(() -> authWith(scope.authority()), new Object()).isGranted())
        .isFalse();
  }

  @Test
  @DisplayName("Should deny narrower scope when authority points the wrong direction")
  void shouldDenyNarrowerScopeWhenAuthorityPointsWrongDirection() {
    var profileCheck = factory.hasAuthority(TokenScope.PROFILE.authority());

    assertThat(
            profileCheck
                .authorize(() -> authWith(TokenScope.ACCOUNT.authority()), new Object())
                .isGranted())
        .isFalse();
  }

  private static DefaultAuthorizationManagerFactory<Object> buildFactory() {
    var factory = new DefaultAuthorizationManagerFactory<Object>();
    factory.setRoleHierarchy(ScopeHierarchy.roleHierarchy());
    return factory;
  }

  private static Authentication authWith(String authority) {
    return new TestingAuthenticationToken("subject", "n/a", authority);
  }
}
