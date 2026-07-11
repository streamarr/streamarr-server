package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.repositories.auth.HouseholdRepository;

public class FakeHouseholdRepository extends FakeJpaRepository<Household>
    implements HouseholdRepository {}
