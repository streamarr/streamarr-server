package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortValue;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class FakeHouseholdRepository extends FakeJpaRepository<Household>
    implements HouseholdRepository {

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
  public boolean tryRename(UUID householdId, String name) {
    var household = findById(householdId);
    household.ifPresent(renamed -> renamed.setName(name));
    return household.isPresent();
  }
}
