package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.IdentityQueryService;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {
      MeResolver.class,
      SecurityContextAuthorizationService.class,
      StreamarrDataFetcherExceptionHandler.class
    })
@DisplayName("Me Resolver Tests")
class MeResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private IdentityQueryService identityQueryService;
  @MockitoBean private ProfileRepository profileRepository;
  @MockitoBean private AccountProfileRepository accountProfileRepository;

  private final UUID accountId = UUID.randomUUID();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should return memberships when account scoped")
  void shouldReturnMembershipsWhenAccountScoped() {
    authenticateAtAccountScope();
    var account = AccountFixture.defaultAccountBuilder().id(accountId).build();
    var profileId = UUID.randomUUID();
    when(identityQueryService.meView(any()))
        .thenReturn(
            new IdentityQueryService.MeView(
                account,
                TokenScope.ACCOUNT,
                List.of(
                    new IdentityQueryService.MembershipView(
                        UUID.randomUUID(),
                        "Home",
                        HouseholdRole.OWNER,
                        List.of(
                            new IdentityQueryService.SelectableProfileView(
                                profileId, "Andrew", false))))));

    var householdName =
        dgsQueryExecutor.executeAndExtractJsonPath(
            "{ me { email scope memberships { householdName householdRole profiles { name active } } } }",
            "data.me.memberships[0].householdName");

    assertThat(householdName).isEqualTo("Home");
  }

  @Test
  @DisplayName("Should return profile required code when no active profile")
  void shouldReturnProfileRequiredCodeWhenNoActiveProfile() {
    authenticateAtAccountScope();
    when(identityQueryService.meView(any())).thenThrow(new ProfileRequiredException());

    var result = dgsQueryExecutor.execute("{ me { email } }");

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().getFirst().getExtensions())
        .containsEntry("code", "PROFILE_REQUIRED");
  }

  private void authenticateAtAccountScope() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(accountId)
            .role(AccountRole.USER)
            .sessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new StreamarrAuthenticationToken(
                identity, null, List.of(new SimpleGrantedAuthority("SCOPE_ACCOUNT"))));
  }
}
