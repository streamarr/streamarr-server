package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountInvitationReoffer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountInvitationReofferRepository
    extends JpaRepository<AccountInvitationReoffer, UUID> {

  List<AccountInvitationReoffer> findByInvitationId(UUID invitationId);
}
