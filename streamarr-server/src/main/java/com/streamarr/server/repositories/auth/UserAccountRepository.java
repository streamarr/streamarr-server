package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserAccountRepository
    extends JpaRepository<UserAccount, UUID>, UserAccountRepositoryCustom {

  Optional<UserAccount> findByEmailIgnoreCase(String email);
}
