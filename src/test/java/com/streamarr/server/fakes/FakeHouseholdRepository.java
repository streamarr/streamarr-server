package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class FakeHouseholdRepository extends FakeJpaRepository<Household>
    implements HouseholdRepository {

  private final Set<UUID> lockedHouseholds = ConcurrentHashMap.newKeySet();

  @Override
  public boolean lockById(UUID householdId) {
    return existsById(householdId) && lockedHouseholds.add(householdId);
  }

  @Override
  public void refresh(Household household) {
    // Changes are made directly to the fake's stored instance.
  }

  @Override
  public List<Household> findAdministrationPage(MediaPaginationOptions options) {
    var comparator =
        Comparator.comparing(Household::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Household::getId);
    var reverse =
        options.getPaginationOptions().getPaginationDirection() == PaginationDirection.REVERSE;
    var cursorId = options.getCursorId();
    var cursorName =
        options.getMediaFilter().getPreviousSortFieldValue() == null
            ? null
            : options.getMediaFilter().getPreviousSortFieldValue().toString();
    var extraRows = cursorId.isPresent() ? 2 : 1;
    var limit = options.getPaginationOptions().getLimit() + extraRows;
    var cursor = cursorId.flatMap(this::findById);
    var afterCursor =
        database.values().stream()
            .filter(household -> cursorId.map(id -> !id.equals(household.getId())).orElse(true))
            .filter(
                household ->
                    cursorName == null
                        || compare(household, cursorName, cursorId.orElseThrow())
                                * (reverse ? -1 : 1)
                            > 0)
            .sorted(reverse ? comparator.reversed() : comparator)
            .toList();
    var page = Stream.concat(cursor.stream(), afterCursor.stream()).limit(limit).toList();
    return reverse ? page.reversed() : page;
  }

  private int compare(Household household, String cursorName, UUID cursorId) {
    var nameComparison = household.getName().compareToIgnoreCase(cursorName);
    return nameComparison != 0 ? nameComparison : household.getId().compareTo(cursorId);
  }

  @Override
  public boolean tryRename(UUID householdId, String name) {
    var household = findById(householdId);
    household.ifPresent(renamed -> renamed.setName(name));
    return household.isPresent();
  }
}
