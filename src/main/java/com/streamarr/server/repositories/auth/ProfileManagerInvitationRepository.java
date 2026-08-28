package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfileManagerInvitationRepository
    extends JpaRepository<ProfileManagerInvitation, UUID>,
        ProfileManagerInvitationRepositoryCustom {

  Optional<ProfileManagerInvitation> findByPublicId(String publicId);
}
