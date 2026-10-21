package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortValue;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class FakeHouseholdRepository extends FakeJpaRepository<Household>
    implements HouseholdRepository {

  private final FakeUserAccountRepository accounts;

  public FakeHouseholdRepository() {
    this(new FakeUserAccountRepository());
  }

  /** A repository that reads which Households each of these Accounts may use. */
  public FakeHouseholdRepository(FakeUserAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  public Set<UUID> lockByIds(Set<UUID> householdIds) {
    return householdIds.stream().filter(this::existsById).collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public boolean lockById(UUID householdId) {
    return existsById(householdId);
  }

  @Override
  public void refresh(Household household) {
    // The fake stores and returns the same mutable instance.
  }

  @Override
  public List<Row<Household>> rowsBeyond(Seek seek) {
    var rows =
        database.values().stream()
            .map(
                household ->
                    new Row<>(
                        household,
                        new Position(
                            new SortValue.OfText(household.getName().toLowerCase(Locale.ROOT)),
                            household.getId())))
            .toList();
    return InMemoryKeyset.rowsBeyond(rows, seek);
  }

  @Override
  public List<Row<Household>> rowsUsableBy(UUID accountId, Seek seek) {
    var ownHouseholdId = accounts.findById(accountId).map(UserAccount::getHouseholdId);
    var rows =
        accounts.findUsableHouseholdIds(accountId).stream()
            .map(this::findById)
            .flatMap(Optional::stream)
            .map(
                household -> {
                  var own = ownHouseholdId.filter(household.getId()::equals).isPresent();
                  var value = own ? "" : household.getName().toLowerCase(Locale.ROOT);
                  return new Row<>(
                      household, new Position(new SortValue.OfText(value), household.getId()));
                })
            .toList();
    return InMemoryKeyset.rowsBeyond(rows, seek);
  }

  @Override
  public boolean tryRename(UUID householdId, String name) {
    var household = findById(householdId);
    household.ifPresent(renamed -> renamed.setName(name));
    return household.isPresent();
  }
}
