package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Household Accounts list")
class HouseholdAccountsListTest {

  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final HouseholdAccountsList list = new HouseholdAccountsList(accounts);

  @Test
  @DisplayName("Should read only one Household's Accounts when that Household is read alone")
  void shouldReadOnlyOneHouseholdsAccountsWhenThatHouseholdIsReadAlone() {
    var householdId = UUID.randomUUID();
    accounts.save(account(householdId, "Bravo"));
    accounts.save(account(householdId, "alpha"));
    accounts.save(account(UUID.randomUUID(), "Another Household"));
    var seek =
        Seek.builder()
            .order(list.order(householdId))
            .position(Optional.empty())
            .direction(PaginationDirection.FORWARD)
            .limit(10)
            .build();

    var rows = list.rowsBeyond(householdId, seek);

    assertThat(rows)
        .extracting(Row::item)
        .extracting(UserAccount::getDisplayName)
        .containsExactly("alpha", "Bravo");
  }

  private static UserAccount account(UUID householdId, String displayName) {
    return AccountFixture.defaultAccountBuilder()
        .householdId(householdId)
        .displayName(displayName)
        .build();
  }
}
