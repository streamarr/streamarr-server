package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.UserAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that every {@link UserAccountRepository} keeps, so the fake that unit tests use behaves
 * as the PostgreSQL repository does.
 */
public interface UserAccountRepositoryContract {

  UserAccountRepository repository();

  /** Creates a Household whose first Account is its HouseholdAdmin, and answers that Account. */
  UserAccount createHouseholdAdmin();

  /** Creates a HouseholdMember of the Account's Household, and answers the member. */
  UserAccount createHouseholdMemberBeside(UserAccount account);

  @Test
  @DisplayName(
      "Should answer only the Household's HouseholdAdmins when locking its HouseholdAdmins")
  default void shouldAnswerOnlyTheHouseholdsHouseholdAdminsWhenLockingItsHouseholdAdmins() {
    var admin = createHouseholdAdmin();
    createHouseholdMemberBeside(admin);
    createHouseholdAdmin();

    assertThat(repository().lockHouseholdAdminIds(admin.getHouseholdId()))
        .containsExactly(admin.getId());
  }
}
