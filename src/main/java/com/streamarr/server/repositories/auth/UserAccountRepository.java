package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.UserAccount;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserAccountRepository
    extends JpaRepository<UserAccount, UUID>, UserAccountRepositoryCustom {

  List<UserAccount> findByHouseholdId(UUID householdId);

  Optional<UserAccount> findByPersonalProfileId(UUID profileId);

  /** The Accounts that these Profiles represent, one per Personal Profile among them. */
  List<UserAccount> findByPersonalProfileIdIn(Collection<UUID> profileIds);
}
