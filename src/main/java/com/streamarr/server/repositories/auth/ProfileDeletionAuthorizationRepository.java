package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileDeletionAuthorization;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfileDeletionAuthorizationRepository
    extends JpaRepository<ProfileDeletionAuthorization, UUID> {}
