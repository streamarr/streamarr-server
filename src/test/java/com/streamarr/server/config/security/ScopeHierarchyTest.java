package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.DefaultAuthorizationManagerFactory;
import org.springframework.security.core.Authentication;

@Tag("UnitTest")
@DisplayName("Scope Hierarchy Tests")
class ScopeHierarchyTest {

  private final DefaultAuthorizationManagerFactory<Object> factory = buildFactory();

  @Test
  @DisplayName("Should grant account scope when profile scoped authority present")
  void shouldGrantAccountScopeWhenProfileScopedAuthorityPresent() {
    var accountCheck = factory.hasAuthority("SCOPE_ACCOUNT");

    assertThat(accountCheck.authorize(() -> authWith("SCOPE_PROFILE"), new Object()).isGranted())
        .isTrue();
    assertThat(accountCheck.authorize(() -> authWith("SCOPE_HOUSEHOLD"), new Object()).isGranted())
        .isTrue();
    assertThat(accountCheck.authorize(() -> authWith("SCOPE_ACCOUNT"), new Object()).isGranted())
        .isTrue();
  }

  @Test
  @DisplayName("Should keep playback scope outside API hierarchy")
  void shouldKeepPlaybackScopeOutsideApiHierarchy() {
    var accountCheck = factory.hasAuthority("SCOPE_ACCOUNT");

    assertThat(accountCheck.authorize(() -> authWith("SCOPE_PLAYBACK"), new Object()).isGranted())
        .isFalse();
  }

  @Test
  @DisplayName("Should deny narrower scope when authority points the wrong direction")
  void shouldDenyNarrowerScopeWhenAuthorityPointsWrongDirection() {
    var profileCheck = factory.hasAuthority("SCOPE_PROFILE");

    assertThat(profileCheck.authorize(() -> authWith("SCOPE_ACCOUNT"), new Object()).isGranted())
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
