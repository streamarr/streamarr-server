package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.PasswordResetCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PasswordResetCodeRepository
    extends JpaRepository<PasswordResetCode, UUID>, PasswordResetCodeRepositoryCustom {

  Optional<PasswordResetCode> findByPublicId(String publicId);
}
