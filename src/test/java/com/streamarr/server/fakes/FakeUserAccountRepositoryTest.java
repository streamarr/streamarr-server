package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
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

  @Test
  @DisplayName("Should reject a restricted Profile owner as an eligible Profile manager")
  void shouldRejectRestrictedProfileOwnerAsEligibleProfileManager() {
    var householdId = UUID.randomUUID();
    var profiles = new FakeProfileRepository();
    var restrictedProfile =
        profiles.save(ProfileFixture.kidProfileBuilder().householdId(householdId).build());
    var accounts = new FakeUserAccountRepository(profiles);
    var manager =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(householdId)
                .householdRole(HouseholdRole.ADMIN)
                .personalProfileId(restrictedProfile.getId())
                .build());

    assertThat(restrictedProfile.isRestricted()).isTrue();
    assertThat(accounts.isEligibleProfileManager(manager.getId(), householdId, true)).isFalse();
  }
}
