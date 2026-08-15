package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public class FakeUserAccountRepository extends FakeJpaRepository<UserAccount>
    implements UserAccountRepository {

  @Override
  public boolean lockIfCredentialsUnchanged(UUID accountId, String expectedPasswordHash) {
    return findById(accountId)
        .filter(UserAccount::isEnabled)
        .filter(account -> account.getPasswordHash().equals(expectedPasswordHash))
        .isPresent();
  }

  @Override
  public <S extends UserAccount> S save(S entity) {
    // Mirrors uq_user_account_email on lower(email).
    var duplicateEmail =
        database.values().stream()
            .anyMatch(
                account ->
                    !account.getId().equals(entity.getId())
                        && account.getEmail().equalsIgnoreCase(entity.getEmail()));

    if (duplicateEmail) {
      // Mirrors Spring's Hibernate translation: the constraint name rides the cause chain.
      var message = "duplicate key value violates unique constraint \"uq_user_account_email\"";
      throw new DataIntegrityViolationException(
          message,
          new ConstraintViolationException(
              message, new SQLException(message, "23505"), "uq_user_account_email"));
    }

    return super.save(entity);
  }

  @Override
  public Optional<UserAccount> findByEmailIgnoreCase(String email) {
    return database.values().stream()
        .filter(account -> account.getEmail().equalsIgnoreCase(email))
        .findFirst();
  }

  /**
   * Finds the first account with the specified home household ID and owner role.
   *
   * @param homeHouseholdId the home household identifier
   * @return the matching owner account, if one exists
   */
  @Override
  public Optional<UserAccount> findOwnerByHomeHouseholdId(UUID homeHouseholdId) {
    return database.values().stream()
        .filter(account -> homeHouseholdId.equals(account.getHomeHouseholdId()))
        .filter(account -> account.getHouseholdRole() == HouseholdRole.OWNER)
        .findFirst();
  }
}
