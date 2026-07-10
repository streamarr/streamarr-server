package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.MembershipVersionChange;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdMembershipRepositoryCustom {

  MembershipVersionChange grantMembership(HouseholdMembership membership);

  Optional<MembershipVersionChange> revokeMembership(UUID accountId, UUID householdId);
}
