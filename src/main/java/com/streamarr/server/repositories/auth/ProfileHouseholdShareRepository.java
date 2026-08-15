package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfileHouseholdShareRepository
    extends JpaRepository<ProfileHouseholdShare, UUID>, ProfileHouseholdShareRepositoryCustom {

  /**
       * Finds household shares with the specified status.
       *
       * @param householdId the household identifier
       * @param status      the required share status
       * @return the matching household shares
       */
      List<ProfileHouseholdShare> findByHouseholdIdAndStatus(
      UUID householdId, ProfileShareStatus status);

  /**
       * Finds household shares for the specified households and status.
       *
       * @param householdIds the household identifiers to search
       * @param status       the required share status
       * @return the matching household shares
       */
      List<ProfileHouseholdShare> findByHouseholdIdInAndStatus(
      Collection<UUID> householdIds, ProfileShareStatus status);

  /**
 * Finds shares for a profile with the specified status.
 *
 * @param profileId the profile identifier
 * @param status    the share status to filter by
 * @return the matching profile shares
 */
List<ProfileHouseholdShare> findByProfileIdAndStatus(UUID profileId, ProfileShareStatus status);

  /**
 * Retrieves all household shares associated with a profile.
 *
 * @param profileId the profile identifier
 * @return the profile's household shares
 */
List<ProfileHouseholdShare> findByProfileId(UUID profileId);

  /**
       * Determines whether a profile-household share with the specified status exists.
       *
       * @param profileId   the profile identifier
       * @param householdId the household identifier
       * @param status      the share status
       * @return {@code true} if a matching share exists, {@code false} otherwise
       */
      boolean existsByProfileIdAndHouseholdIdAndStatus(
      UUID profileId, UUID householdId, ProfileShareStatus status);

  /**
 * Finds the share associated with a profile and household.
 *
 * @param profileId   the profile identifier
 * @param householdId the household identifier
 * @return the matching share, if one exists
 */
Optional<ProfileHouseholdShare> findByProfileIdAndHouseholdId(UUID profileId, UUID householdId);

  /**
 * Counts the household shares associated with a profile.
 *
 * @param profileId the profile identifier
 * @return the number of shares associated with the profile
 */
long countByProfileId(UUID profileId);
}
