package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.HouseholdMembership;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HouseholdMembershipRepository extends JpaRepository<HouseholdMembership, UUID> {

  Optional<HouseholdMembership> findByAccountIdAndHouseholdId(UUID accountId, UUID householdId);
}
