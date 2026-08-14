package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.RequestAuthorizationStateResolver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("UnitTest")
@DisplayName("Live Identity Authorization Filter Tests")
class LiveIdentityAuthorizationFilterTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should fail closed when live authorization cannot read database state")
  void shouldFailClosedWhenLiveAuthorizationCannotReadDatabaseState() throws Exception {
    var stateResolver = mock(RequestAuthorizationStateResolver.class);
    var token = accountToken();
    when(stateResolver.resolve(token))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    SecurityContextHolder.getContext().setAuthentication(token);
    var filter =
        new LiveIdentityAuthorizationFilter(stateResolver, new RestAuthenticationEntryPoint());
    var response = new MockHttpServletResponse();
    var chainInvoked = new AtomicBoolean();

    filter.doFilter(new MockHttpServletRequest(), response, (_, _) -> chainInvoked.set(true));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication is required.\"}");
    assertThat(chainInvoked).isFalse();
  }

  private StreamarrAuthenticationToken accountToken() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .build();
    var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("account").build();
    return new StreamarrAuthenticationToken(
        identity, jwt, List.of(new SimpleGrantedAuthority(TokenScope.ACCOUNT.authority())));
  }
}
