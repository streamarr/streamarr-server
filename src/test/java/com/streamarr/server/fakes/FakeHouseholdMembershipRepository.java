package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;

public class FakeHouseholdMembershipRepository extends FakeJpaRepository<HouseholdMembership>
    implements HouseholdMembershipRepository {}
