package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfileManagerInvitationRepository
    extends JpaRepository<ProfileManagerInvitation, UUID>,
        ProfileManagerInvitationRepositoryCustom {

  /**
 * Counts invitations for a profile with the specified status.
 *
 * @param profileId the profile identifier
 * @param status    the invitation status to match
 * @return the number of matching invitations
 */
long countByProfileIdAndStatus(UUID profileId, ProfileManagerInvitationStatus status);

  /**
 * Retrieves all invitations associated with a profile.
 *
 * @param profileId the profile identifier
 * @return the invitations associated with the profile
 */
List<ProfileManagerInvitation> findByProfileId(UUID profileId);
}
