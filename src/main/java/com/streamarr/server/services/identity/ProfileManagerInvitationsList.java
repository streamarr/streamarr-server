package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValueKind;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** One Profile's live pending manager invitations, newest first. */
@Component
@RequiredArgsConstructor
public class ProfileManagerInvitationsList implements PagedList<ProfileManagerInvitation, UUID> {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final ProfileManagerInvitationRepository invitationRepository;
  private final Clock clock;

  @Override
  public ListOrder order(UUID profileId) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<ProfileManagerInvitation>> rowsBeyond(UUID profileId, Seek seek) {
    return invitationRepository.rowsPendingForProfile(profileId, clock.instant(), seek);
  }
}
