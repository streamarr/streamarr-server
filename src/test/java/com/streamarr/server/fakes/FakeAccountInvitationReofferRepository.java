package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AccountInvitationReoffer;
import com.streamarr.server.repositories.auth.AccountInvitationReofferRepository;
import java.util.List;
import java.util.UUID;

public class FakeAccountInvitationReofferRepository
    extends FakeJpaRepository<AccountInvitationReoffer>
    implements AccountInvitationReofferRepository {

  @Override
  public List<AccountInvitationReoffer> findByInvitationId(UUID invitationId) {
    return database.values().stream()
        .filter(reoffer -> reoffer.getInvitationId().equals(invitationId))
        .toList();
  }
}
