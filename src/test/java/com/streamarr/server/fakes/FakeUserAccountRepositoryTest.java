package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.AccountFixture;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Fake User Account Repository Tests")
class FakeUserAccountRepositoryTest {

  @Test
  @DisplayName("Should return each usable Household once when active shares are duplicated")
  void shouldReturnEachUsableHouseholdOnceWhenActiveSharesAreDuplicated() {
    var shares = new FakeProfileHouseholdShareRepository();
    var accounts = new FakeUserAccountRepository(shares);
    var account = accounts.save(AccountFixture.defaultAccountBuilder().build());
    var visitedHouseholdId = UUID.randomUUID();
    shares.share(account.getPersonalProfileId(), visitedHouseholdId, false);
    shares.share(account.getPersonalProfileId(), visitedHouseholdId, false);

    assertThat(accounts.findUsableHouseholdIds(account.getId()))
        .containsExactly(account.getHouseholdId(), visitedHouseholdId);
  }
}
