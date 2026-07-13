package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.HouseholdMembership;
import java.util.UUID;

public interface HouseholdMembershipRepositoryCustom {

  void grantMembership(HouseholdMembership membership);

  boolean changeRole(HouseholdMembership membership);

  boolean revokeMembership(UUID accountId, UUID householdId);
}
