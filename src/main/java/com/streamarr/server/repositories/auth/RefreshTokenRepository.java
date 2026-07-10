package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.RefreshToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository
    extends JpaRepository<RefreshToken, UUID>, RefreshTokenRepositoryCustom {

  Optional<RefreshToken> findByDigest(String digest);
}
