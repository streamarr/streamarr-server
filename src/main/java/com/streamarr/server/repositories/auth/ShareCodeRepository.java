package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.ShareCodeStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShareCodeRepository
    extends JpaRepository<ShareCode, UUID>, ShareCodeRepositoryCustom {

  Optional<ShareCode> findByCodeDigest(byte[] codeDigest);

  List<ShareCode> findByIssuerAccountIdAndStatus(UUID issuerAccountId, ShareCodeStatus status);
}
