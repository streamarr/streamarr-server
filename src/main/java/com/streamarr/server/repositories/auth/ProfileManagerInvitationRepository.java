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

  long countByProfileIdAndStatus(UUID profileId, ProfileManagerInvitationStatus status);

  List<ProfileManagerInvitation> findByProfileId(UUID profileId);
}
