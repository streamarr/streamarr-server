package com.streamarr.server.services.identity;

import static com.streamarr.server.fixtures.PaginationFixture.firstRows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.fixtures.ProfileHouseholdShareFixture;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.services.identity.IdentityQueryService.UsableHouseholdDetails;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Usable Household paging")
class UsableHouseholdsPagedListIT
    extends PagedListContract<UsableHouseholdDetails, UsableHouseholdsPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);

  @Autowired private IdentityQueryService identityQuery;
  @Autowired private ProfileSharingService profileSharing;
  @Autowired private HouseholdRepository households;
  @Autowired private ProfileHouseholdShareRepository shares;
  @Autowired private AuthTestSupport auth;

  /**
   * An Account whose own Household is rank 0, and the Households its Personal Profile visits, named
   * with a mixed-case prefix so equal ranks share a name.
   */
  record Scope(
      AuthTestSupport.TestIdentity account,
      String namePrefix,
      Map<UUID, UUID> shareByHousehold,
      List<UUID> visitedHouseholds) {}

  @Override
  protected Scope createScope() {
    var prefix =
        IntStream.range(0, 12)
            .mapToObj(_ -> String.valueOf((char) ('a' + ThreadLocalRandom.current().nextInt(26))))
            .collect(Collectors.joining());
    return new Scope(auth.createIdentity(), prefix, new HashMap<>(), new ArrayList<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    auth.deleteIdentity(scope.account());
    households.deleteAllById(scope.visitedHouseholds());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var visited =
        households.saveAndFlush(
            Household.builder().name(scope.namePrefix().toUpperCase() + rank).build());
    var share =
        shares.saveAndFlush(
            ProfileHouseholdShareFixture.activeShareBuilder()
                .profileId(scope.account().profile().getId())
                .householdId(visited.getId())
                .build());
    scope.visitedHouseholds().add(visited.getId());
    scope.shareByHousehold().put(visited.getId(), share.getId());
    return visited.getId();
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    var outcome =
        profileSharing.endProfileShare(
            auth.freshIdentityOf(scope.account()), scope.shareByHousehold().get(item));
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  @Override
  protected ListPage<UsableHouseholdDetails> page(Scope scope, PageRequest request) {
    return identityQuery.usableHouseholds(auth.identityOf(scope.account()), request);
  }

  @Override
  protected UUID idOf(UsableHouseholdDetails household) {
    return household.household().id();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.ASC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return true;
  }

  // Visited names are compared lowercased, and letters sort after digits in every collation.
  @Override
  protected Optional<Position> positionBeforeArrangedItems(Scope scope) {
    return Optional.of(new Position(new SortValue.OfText(scope.namePrefix()), LOWEST_ID));
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.of(new Position(new SortValue.OfText(scope.namePrefix() + "z"), LOWEST_ID));
  }

  @Test
  @DisplayName("Should list the Account's own Household first when it visits one named before it")
  void shouldListTheAccountsOwnHouseholdFirstWhenItVisitsOneNamedBeforeIt() {
    var scope = createScope();
    try {
      var visited = add(scope, 0);
      var renamed = households.findById(visited).orElseThrow();
      renamed.setName("Aardvark");
      households.saveAndFlush(renamed);

      var page = page(scope, firstRows(2));

      assertThat(page.rows())
          .extracting(row -> row.item().household().id(), row -> row.item().membership())
          .containsExactly(tuple(scope.account().household().getId(), true), tuple(visited, false));
      assertThat(page.hasNextPage()).isFalse();
    } finally {
      deleteScope(scope);
    }
  }
}
