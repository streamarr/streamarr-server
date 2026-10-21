package com.streamarr.server.graphql.dataloaders;

import static com.streamarr.server.fixtures.PaginationFixture.firstRows;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.services.identity.AdministrationQueryService;
import com.streamarr.server.services.identity.HouseholdAccountsList;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Administration Accounts Data Loader Tests")
class AdministrationAccountsDataLoaderTest {

  private final RecordingUserAccountRepository accounts = new RecordingUserAccountRepository();
  private final AdministrationAccountsDataLoader loader =
      new AdministrationAccountsDataLoader(
          AdministrationQueryService.builder()
              .householdAccountsList(new HouseholdAccountsList(accounts))
              .build());

  @Test
  @DisplayName(
      "Should read every Household's Accounts together when Households are loaded together")
  void shouldReadEveryHouseholdsAccountsTogetherWhenHouseholdsAreLoadedTogether() throws Exception {
    var firstHouseholdId = UUID.randomUUID();
    var secondHouseholdId = UUID.randomUUID();
    accounts.save(account(firstHouseholdId, "First Account"));
    accounts.save(account(secondHouseholdId, "Second Account"));
    var firstKey = new AdministrationAccountsLoaderKey(firstHouseholdId, firstRows(10));
    var secondKey = new AdministrationAccountsLoaderKey(secondHouseholdId, firstRows(5));

    var result = loader.load(Set.of(firstKey, secondKey)).toCompletableFuture().get();

    assertThat(accounts.requestedHouseholds)
        .containsExactly(Set.of(firstHouseholdId, secondHouseholdId));
    assertThat(result).containsOnlyKeys(firstKey, secondKey);
    assertThat(displayNames(result.get(firstKey))).containsExactly("First Account");
    assertThat(displayNames(result.get(secondKey))).containsExactly("Second Account");
  }

  @Test
  @DisplayName("Should answer each request when one Household is loaded with different arguments")
  void shouldAnswerEachRequestWhenOneHouseholdIsLoadedWithDifferentArguments() throws Exception {
    var householdId = UUID.randomUUID();
    accounts.save(account(householdId, "Alpha"));
    accounts.save(account(householdId, "Bravo"));
    var oneAccount = new AdministrationAccountsLoaderKey(householdId, firstRows(1));
    var twoAccounts = new AdministrationAccountsLoaderKey(householdId, firstRows(2));

    var result = loader.load(Set.of(oneAccount, twoAccounts)).toCompletableFuture().get();

    assertThat(displayNames(result.get(oneAccount))).containsExactly("Alpha");
    assertThat(displayNames(result.get(twoAccounts))).containsExactly("Alpha", "Bravo");
    assertThat(accounts.requestedHouseholds)
        .containsExactly(Set.of(householdId), Set.of(householdId));
  }

  private static List<String> displayNames(ListPage<UserAccount> page) {
    return page.rows().stream().map(Row::item).map(UserAccount::getDisplayName).toList();
  }

  private static UserAccount account(UUID householdId, String displayName) {
    return AccountFixture.defaultAccountBuilder()
        .householdId(householdId)
        .displayName(displayName)
        .build();
  }

  private static final class RecordingUserAccountRepository extends FakeUserAccountRepository {

    private final List<Set<UUID>> requestedHouseholds = new ArrayList<>();

    @Override
    public Map<UUID, List<Row<UserAccount>>> rowsBeyond(Map<UUID, Seek> seeksByHousehold) {
      requestedHouseholds.add(Set.copyOf(seeksByHousehold.keySet()));
      return super.rowsBeyond(seeksByHousehold);
    }
  }
}
