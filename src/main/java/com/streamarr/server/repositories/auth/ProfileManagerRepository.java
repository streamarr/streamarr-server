package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfileManagerRepository
    extends JpaRepository<ProfileManager, UUID>, ProfileManagerRepositoryCustom {

  /**
 * Determines whether an account is associated with a profile.
 *
 * @param accountId  the account identifier
 * @param profileId  the profile identifier
 * @return           {@code true} if the association exists, {@code false} otherwise
 */
boolean existsByAccountIdAndProfileId(UUID accountId, UUID profileId);

  /**
 * Retrieves all managers associated with a profile.
 *
 * @param profileId the profile identifier
 * @return the managers associated with the profile
 */
List<ProfileManager> findByProfileId(UUID profileId);

  /**
 * Retrieves all profile-manager associations for an account.
 *
 * @param accountId the account identifier
 * @return the profile-manager associations associated with the account
 */
List<ProfileManager> findByAccountId(UUID accountId);

  /**
 * Locates the association for the specified account and profile.
 *
 * @param accountId the account identifier
 * @param profileId the profile identifier
 * @return the matching association, if one exists
 */
Optional<ProfileManager> findByAccountIdAndProfileId(UUID accountId, UUID profileId);

  /**
 * Counts the managers associated with a profile.
 *
 * @param profileId the profile identifier
 * @return the number of managers associated with the profile
 */
long countByProfileId(UUID profileId);
}
