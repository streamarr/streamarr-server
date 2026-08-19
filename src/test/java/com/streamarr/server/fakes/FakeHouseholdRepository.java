package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import java.util.UUID;

public class FakeHouseholdRepository extends FakeJpaRepository<Household>
    implements HouseholdRepository {

  @Override
  public boolean tryRename(UUID householdId, String name) {
    var household = findById(householdId);
    household.ifPresent(renamed -> renamed.setName(name));
    return household.isPresent();
  }
}
