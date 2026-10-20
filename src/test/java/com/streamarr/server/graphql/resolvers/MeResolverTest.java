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
import com.streamarr.server.fakes.InMemoryPagedList;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.identity.IdentityQueryService;
import com.streamarr.server.services.identity.IdentityQueryService.HouseholdSummaryDetails;
import com.streamarr.server.services.identity.IdentityQueryService.SelectableProfileDetails;
import com.streamarr.server.services.identity.IdentityQueryService.UsableHouseholdDetails;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.services.pagination.SortValueKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
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
      RelayConnectionAdapter.class,
      PositionCursorCodec.class,
      JacksonAutoConfiguration.class,
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

  @Autowired private PositionCursorCodec positionCursors;

  @MockitoBean private IdentityQueryService identityQueryService;

  @MockitoBean private UserAccountRepository userAccountRepository;

  private final UUID accountId = UUID.randomUUID();
  private final UUID householdId = UUID.randomUUID();
  private final UUID visitedHouseholdId = UUID.randomUUID();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("Should return the Me response with paged Households and Profiles when queried")
  void shouldReturnMeShapeWithPagedHouseholdsAndProfilesWhenQueried() {
    authenticateAtAccountScope();
    var account =
        AccountFixture.defaultAccountBuilder()
            .id(accountId)
            .householdId(householdId)
            .householdRole(HouseholdRole.ADMIN)
            .build();
    var home = new HouseholdSummaryDetails(householdId, "Home");
    var visited = new HouseholdSummaryDetails(visitedHouseholdId, "Grandma");
    var personal =
        SelectableProfileDetails.builder()
            .id(UUID.randomUUID())
            .name("Andrew")
            .picture(Optional.empty())
            .kind(ProfileKind.ADULT)
            .personal(true)
            .pinConfigured(false)
            .locked(false)
            .selected(false)
            .build();
    var kid =
        SelectableProfileDetails.builder()
            .id(UUID.randomUUID())
            .name("Kai")
            .picture(Optional.of("kai.png"))
            .kind(ProfileKind.KID)
            .personal(false)
            .pinConfigured(true)
            .locked(false)
            .selected(false)
            .build();
    var details =
        IdentityQueryService.MeDetails.builder()
            .account(account)
            .scope(TokenScope.ACCOUNT)
            .household(home)
            .contextHousehold(home)
            .deviceBound(false)
            .build();
    when(identityQueryService.meDetails(any())).thenReturn(details);
    stubPages(
        List.of(new UsableHouseholdDetails(home, true), new UsableHouseholdDetails(visited, false)),
        List.of(personal, kid),
        null);

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
  @DisplayName("Should return the requested last Profiles when before is omitted")
  void shouldReturnRequestedLastProfilesWhenBeforeIsOmitted() {
    stubTwoSelectableProfiles();
    var query =
        "{ me { selectableProfiles(last: 1) { edges { node { name } } pageInfo { hasNextPage hasPreviousPage } } } }";

    List<String> names =
        dgsQueryExecutor.executeAndExtractJsonPath(
            query, "data.me.selectableProfiles.edges[*].node.name");
    Boolean hasPrevious =
        dgsQueryExecutor.executeAndExtractJsonPath(
            query, "data.me.selectableProfiles.pageInfo.hasPreviousPage");

    assertThat(names).containsExactly("Kai");
    assertThat(hasPrevious).isTrue();
  }

  @Test
  @DisplayName("Should reject a Profile cursor when it continues another Household's picker")
  void shouldRejectAProfileCursorWhenItContinuesAnotherHouseholdsPicker() {
    stubTwoSelectableProfiles();
    var cursor =
        positionCursors
            .encode(
                new ListIdentity("selectable-profiles", UUID.randomUUID()),
                new Position(new SortValue.OfText("andrew"), UUID.randomUUID()))
            .getValue();

    var result =
        dgsQueryExecutor.execute(
            "{ me { selectableProfiles(first: 1, after: \"%s\") { edges { node { name } } } } }"
                .formatted(cursor));

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().getFirst().getExtensions())
        .containsEntry("code", "INVALID_CURSOR");
  }

  @Test
  @DisplayName("Should reject a Household cursor when it continues another Account's Households")
  void shouldRejectAHouseholdCursorWhenItContinuesAnotherAccountsHouseholds() {
    stubTwoSelectableProfiles();
    var cursor =
        positionCursors
            .encode(
                new ListIdentity("usable-households", UUID.randomUUID()),
                new Position(new SortValue.OfText("home"), UUID.randomUUID()))
            .getValue();

    var result =
        dgsQueryExecutor.execute(
            "{ me { usableHouseholds(first: 1, after: \"%s\") { edges { node { membership } } } } }"
                .formatted(cursor));

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().getFirst().getExtensions())
        .containsEntry("code", "INVALID_CURSOR");
  }

  @Test
  @DisplayName("Should return profile required code when the query service demands a profile")
  void shouldReturnProfileRequiredCodeWhenQueryServiceDemandsProfile() {
    authenticateAtAccountScope();
    when(identityQueryService.meDetails(any())).thenThrow(new ProfileRequiredException());

    var result = dgsQueryExecutor.execute("{ me { email } }");

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().getFirst().getExtensions())
        .containsEntry("code", "PROFILE_REQUIRED");
  }

  private void stubTwoSelectableProfiles() {
    authenticateAtAccountScope();
    var account =
        AccountFixture.defaultAccountBuilder().id(accountId).householdId(householdId).build();
    var home = new HouseholdSummaryDetails(householdId, "Home");
    var first =
        SelectableProfileDetails.builder()
            .id(UUID.randomUUID())
            .name("Andrew")
            .picture(Optional.empty())
            .kind(ProfileKind.ADULT)
            .personal(true)
            .pinConfigured(false)
            .locked(false)
            .selected(true)
            .build();
    var second =
        SelectableProfileDetails.builder()
            .id(UUID.randomUUID())
            .name("Kai")
            .picture(Optional.empty())
            .kind(ProfileKind.KID)
            .personal(false)
            .pinConfigured(true)
            .locked(false)
            .selected(false)
            .build();
    var details =
        IdentityQueryService.MeDetails.builder()
            .account(account)
            .scope(TokenScope.PROFILE)
            .household(home)
            .contextHousehold(home)
            .deviceBound(false)
            .build();
    when(identityQueryService.meDetails(any())).thenReturn(details);
    stubPages(List.of(new UsableHouseholdDetails(home, true)), List.of(first, second), first);
  }

  private void stubPages(
      List<UsableHouseholdDetails> households,
      List<SelectableProfileDetails> profiles,
      SelectableProfileDetails selected) {
    var householdList =
        new InMemoryPagedList<UsableHouseholdDetails, Object>(
            ListOrder.builder()
                .valueKind(SortValueKind.NUMBER)
                .direction(SortDirection.ASC)
                .build(),
            households.stream()
                .map(
                    household ->
                        new Row<>(
                            household,
                            new Position(
                                new SortValue.OfNumber(household.membership() ? 0 : 1),
                                household.household().id())))
                .toList());
    when(identityQueryService.usableHouseholds(any(), any(PageRequest.class)))
        .thenAnswer(invocation -> Pager.page(householdList, accountId, invocation.getArgument(1)));
    var profileList =
        new InMemoryPagedList<SelectableProfileDetails, Object>(
            ListOrder.builder().valueKind(SortValueKind.TEXT).direction(SortDirection.ASC).build(),
            profiles.stream()
                .map(
                    profile ->
                        new Row<>(
                            profile,
                            new Position(new SortValue.OfText(profile.name()), profile.id())))
                .toList());
    when(identityQueryService.selectableProfiles(any(), any(PageRequest.class)))
        .thenAnswer(invocation -> Pager.page(profileList, householdId, invocation.getArgument(1)));
    when(identityQueryService.selectedProfile(any())).thenReturn(Optional.ofNullable(selected));
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
