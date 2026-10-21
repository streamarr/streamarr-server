package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
@DisplayName("Household Account administration paging")
class HouseholdAccountsPagedListIT
    extends PagedListContract<UserAccount, HouseholdAccountsPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);

  @Autowired private AdministrationQueryService administration;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private UserAccountRepository accounts;
  @Autowired private AuthTestSupport auth;

  /**
   * A Household, whose own HouseholdAdmin also lists there, and a display-name prefix only this
   * test's members share.
   */
  record Scope(
      AuthTestSupport.TestIdentity owner,
      String namePrefix,
      Map<UUID, AuthTestSupport.TestIdentity> members) {}

  @Override
  protected Scope createScope() {
    return new Scope(auth.createIdentity(), randomLetters(), new HashMap<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    auth.deleteIdentity(scope.owner());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var member = auth.createHouseholdMemberIdentity(scope.owner());
    var account = accounts.findById(member.account().getId()).orElseThrow();
    account.setDisplayName(scope.namePrefix().toUpperCase() + rank);
    accounts.saveAndFlush(account);
    scope.members().put(account.getId(), member);
    return account.getId();
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    var member = scope.members().remove(item);
    var outcome = accountLifecycle.deleteMyAccount(auth.freshIdentityOf(member), "DELETE");
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  @Override
  protected ListPage<UserAccount> page(Scope scope, PageRequest request) {
    var householdId = scope.owner().household().getId();
    return administration.accountPages(Map.of(householdId, request)).get(householdId);
  }

  @Override
  protected UUID idOf(UserAccount account) {
    return account.getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.ASC;
  }

  @Override
  protected Optional<Position> positionBeforeArrangedItems(Scope scope) {
    return Optional.of(new Position(new SortValue.OfText(scope.namePrefix()), LOWEST_ID));
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.of(new Position(new SortValue.OfText(scope.namePrefix() + "z"), LOWEST_ID));
  }

  @Test
  @DisplayName(
      "Should page each Household from its own position when Households are paged together")
  void shouldPageEachHouseholdFromItsOwnPositionWhenHouseholdsArePagedTogether() {
    var first = createScope();
    var second = createScope();
    try {
      var firstAccounts = IntStream.range(0, 3).mapToObj(rank -> add(first, rank)).toList();
      var secondAccounts = IntStream.range(0, 3).mapToObj(rank -> add(second, rank)).toList();
      var secondMiddle = accounts.findById(secondAccounts.get(1)).orElseThrow();
      var requests = new LinkedHashMap<UUID, PageRequest>();
      requests.put(first.owner().household().getId(), from(positionBeforeArrangedItems(first), 2));
      requests.put(
          second.owner().household().getId(),
          from(
              Optional.of(
                  new Position(
                      new SortValue.OfText(secondMiddle.getDisplayName().toLowerCase()),
                      secondMiddle.getId())),
              1));

      var pages = administration.accountPages(requests);

      assertThat(ids(pages.get(first.owner().household().getId())))
          .containsExactly(firstAccounts.get(0), firstAccounts.get(1));
      assertThat(ids(pages.get(second.owner().household().getId())))
          .containsExactly(secondAccounts.get(2));
    } finally {
      deleteScope(first);
      deleteScope(second);
    }
  }

  private static PageRequest from(Optional<Position> position, int limit) {
    return PageRequest.builder()
        .position(position)
        .direction(PaginationDirection.FORWARD)
        .limit(limit)
        .build();
  }

  private static List<UUID> ids(ListPage<UserAccount> page) {
    return page.rows().stream().map(Row::item).map(UserAccount::getId).toList();
  }

  private static String randomLetters() {
    return IntStream.range(0, 12)
        .mapToObj(_ -> String.valueOf((char) ('a' + ThreadLocalRandom.current().nextInt(26))))
        .collect(Collectors.joining());
  }
}
