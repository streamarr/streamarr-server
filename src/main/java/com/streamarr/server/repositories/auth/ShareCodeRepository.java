package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ShareCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShareCodeRepository
    extends JpaRepository<ShareCode, UUID>, ShareCodeRepositoryCustom {

  Optional<ShareCode> findByCodeDigest(byte[] codeDigest);
}
