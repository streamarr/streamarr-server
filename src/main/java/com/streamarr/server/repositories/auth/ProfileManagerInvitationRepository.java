package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfileManagerInvitationRepository
    extends JpaRepository<ProfileManagerInvitation, UUID>,
        ProfileManagerInvitationRepositoryCustom {

  Optional<ProfileManagerInvitation> findByPublicId(String publicId);

  List<ProfileManagerInvitation> findByRecipientAccountIdAndStatus(
      UUID recipientAccountId, ProfileManagerInvitationStatus status);

  List<ProfileManagerInvitation> findByProfileIdAndStatus(
      UUID profileId, ProfileManagerInvitationStatus status);
}
