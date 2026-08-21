package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.identity.IdentityQueryService;
import com.streamarr.server.services.identity.IdentityQueryService.HouseholdSummaryView;
import com.streamarr.server.services.identity.IdentityQueryService.SelectableProfileView;
import com.streamarr.server.services.identity.IdentityQueryService.UsableHouseholdView;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
      PaginationService.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class,
      StreamarrDataFetcherExceptionHandler.class
    })
@DisplayName("Me Resolver Tests")
class MeResolverTest {

  private static final String ME_QUERY =
      """
      {
        me {
          email
          serverAdmin
          scope
          household { id name }
          householdRole
          contextHousehold { id name }
          usableHouseholds(first: 10) {
            edges { cursor node { household { name } membership } }
            pageInfo { hasNextPage hasPreviousPage }
          }
          selectableProfiles(first: 1) {
            edges { cursor node { id name kind personal pinConfigured locked selected } }
            pageInfo { hasNextPage hasPreviousPage endCursor }
          }
          selectedProfile { name }
          deviceBound
        }
      }
      """;

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private IdentityQueryService identityQueryService;

  private final UUID accountId = UUID.randomUUID();
  private final UUID householdId = UUID.randomUUID();
  private final UUID visitedHouseholdId = UUID.randomUUID();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should return the Me shape with paged Households and Profiles when queried")
  void shouldReturnMeShapeWithPagedHouseholdsAndProfilesWhenQueried() {
    authenticateAtAccountScope();
    var account =
        AccountFixture.defaultAccountBuilder()
            .id(accountId)
            .householdId(householdId)
            .householdRole(HouseholdRole.ADMIN)
            .build();
    var home = new HouseholdSummaryView(householdId, "Home");
    var visited = new HouseholdSummaryView(visitedHouseholdId, "Grandma");
    var personal =
        new SelectableProfileView(
            UUID.randomUUID(),
            "Andrew",
            Optional.empty(),
            ProfileKind.ADULT,
            true,
            false,
            false,
            false);
    var kid =
        new SelectableProfileView(
            UUID.randomUUID(),
            "Kai",
            Optional.of("kai.png"),
            ProfileKind.KID,
            false,
            true,
            false,
            false);
    when(identityQueryService.meView(any()))
        .thenReturn(
            new IdentityQueryService.MeView(
                account,
                TokenScope.ACCOUNT,
                home,
                home,
                List.of(
                    new UsableHouseholdView(home, true), new UsableHouseholdView(visited, false)),
                List.of(personal, kid),
                null,
                false));

    Map<String, Object> me = dgsQueryExecutor.executeAndExtractJsonPath(ME_QUERY, "data.me");

    assertThat(me)
        .containsEntry("scope", "account")
        .containsEntry("serverAdmin", false)
        .containsEntry("householdRole", "ADMIN")
        .containsEntry("deviceBound", false)
        .containsEntry("selectedProfile", null)
        .containsEntry("household", Map.of("id", householdId.toString(), "name", "Home"));
    List<String> households =
        dgsQueryExecutor.executeAndExtractJsonPath(
            ME_QUERY, "data.me.usableHouseholds.edges[*].node.household.name");
    assertThat(households).containsExactly("Home", "Grandma");
    List<Boolean> memberships =
        dgsQueryExecutor.executeAndExtractJsonPath(
            ME_QUERY, "data.me.usableHouseholds.edges[*].node.membership");
    assertThat(memberships).containsExactly(true, false);
    List<String> firstPage =
        dgsQueryExecutor.executeAndExtractJsonPath(
            ME_QUERY, "data.me.selectableProfiles.edges[*].node.name");
    assertThat(firstPage).containsExactly("Andrew");
    Boolean hasNext =
        dgsQueryExecutor.executeAndExtractJsonPath(
            ME_QUERY, "data.me.selectableProfiles.pageInfo.hasNextPage");
    assertThat(hasNext).isTrue();
  }

  @Test
  @DisplayName("Should page Profiles by keyset cursor when after is given")
  void shouldPageProfilesByKeysetCursorWhenAfterIsGiven() {
    stubTwoSelectableProfiles();
    String endCursor =
        dgsQueryExecutor.executeAndExtractJsonPath(
            ME_QUERY, "data.me.selectableProfiles.pageInfo.endCursor");

    var nextPage =
        "{ me { selectableProfiles(first: 5, after: \"%s\") { edges { node { name } } pageInfo { hasNextPage hasPreviousPage } } selectedProfile { name } } }"
            .formatted(endCursor);
    List<String> names =
        dgsQueryExecutor.executeAndExtractJsonPath(
            nextPage, "data.me.selectableProfiles.edges[*].node.name");
    Boolean hasPrevious =
        dgsQueryExecutor.executeAndExtractJsonPath(
            nextPage, "data.me.selectableProfiles.pageInfo.hasPreviousPage");
    String selected =
        dgsQueryExecutor.executeAndExtractJsonPath(nextPage, "data.me.selectedProfile.name");

    assertThat(names).containsExactly("Kai");
    assertThat(hasPrevious).isTrue();
    assertThat(selected).isEqualTo("Andrew");
  }

  @Test
  @DisplayName("Should use the default page size when only a before Profile cursor is given")
  void shouldUseDefaultPageSizeWhenOnlyBeforeProfileCursorIsGiven() {
    stubTwoSelectableProfiles();
    var cursorQuery = "{ me { selectableProfiles(first: 2) { pageInfo { endCursor } } } }";
    String before =
        dgsQueryExecutor.executeAndExtractJsonPath(
            cursorQuery, "data.me.selectableProfiles.pageInfo.endCursor");

    var reverseQuery =
        "{ me { selectableProfiles(before: \"%s\") { edges { node { name } } } } }"
            .formatted(before);
    List<String> names =
        dgsQueryExecutor.executeAndExtractJsonPath(
            reverseQuery, "data.me.selectableProfiles.edges[*].node.name");

    assertThat(names).containsExactly("Andrew");
  }

  @Test
  @DisplayName("Should return profile required code when the query service demands a profile")
  void shouldReturnProfileRequiredCodeWhenQueryServiceDemandsProfile() {
    authenticateAtAccountScope();
    when(identityQueryService.meView(any())).thenThrow(new ProfileRequiredException());

    var result = dgsQueryExecutor.execute("{ me { email } }");

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().getFirst().getExtensions())
        .containsEntry("code", "PROFILE_REQUIRED");
  }

  private void stubTwoSelectableProfiles() {
    authenticateAtAccountScope();
    var account =
        AccountFixture.defaultAccountBuilder().id(accountId).householdId(householdId).build();
    var home = new HouseholdSummaryView(householdId, "Home");
    var first =
        new SelectableProfileView(
            UUID.randomUUID(),
            "Andrew",
            Optional.empty(),
            ProfileKind.ADULT,
            true,
            false,
            false,
            true);
    var second =
        new SelectableProfileView(
            UUID.randomUUID(), "Kai", Optional.empty(), ProfileKind.KID, false, true, false, false);
    when(identityQueryService.meView(any()))
        .thenReturn(
            new IdentityQueryService.MeView(
                account,
                TokenScope.PROFILE,
                home,
                home,
                List.of(new UsableHouseholdView(home, true)),
                List.of(first, second),
                first,
                false));
  }

  private void authenticateAtAccountScope() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(accountId)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .householdId(householdId)
            .householdRole(HouseholdRole.ADMIN)
            .contextHouseholdId(householdId)
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new StreamarrAuthenticationToken(
                identity,
                null,
                List.of(new SimpleGrantedAuthority(TokenScope.ACCOUNT.authority()))));
  }
}
