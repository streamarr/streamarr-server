package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountProfileRepository
    extends JpaRepository<AccountProfile, UUID>, AccountProfileRepositoryCustom {}
