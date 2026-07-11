package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.Profile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfileRepository extends JpaRepository<Profile, UUID> {}
