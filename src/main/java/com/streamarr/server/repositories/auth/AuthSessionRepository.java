package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AuthSession;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthSessionRepository
    extends JpaRepository<AuthSession, UUID>, AuthSessionRepositoryCustom {

  List<AuthSession> findByAccountId(UUID accountId);
}
