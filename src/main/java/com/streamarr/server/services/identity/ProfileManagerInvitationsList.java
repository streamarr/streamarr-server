package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationsFilter;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** One Profile's live pending manager invitations, as one reader reads them, newest first. */
@Component
@RequiredArgsConstructor
public class ProfileManagerInvitationsList
    implements PagedList<ProfileManagerInvitation, ProfileManagerInvitationsFilter> {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final ProfileManagerInvitationRepository invitationRepository;
  private final Clock clock;

  @Override
  public ListOrder order(ProfileManagerInvitationsFilter filter) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<ProfileManagerInvitation>> rowsBeyond(
      ProfileManagerInvitationsFilter filter, Seek seek) {
    return invitationRepository.rowsPendingForProfile(filter, clock.instant(), seek);
  }
}
