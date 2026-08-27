package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountInvitation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountInvitationRepository
    extends JpaRepository<AccountInvitation, UUID>, AccountInvitationRepositoryCustom {

  Optional<AccountInvitation> findByPublicId(String publicId);
}
