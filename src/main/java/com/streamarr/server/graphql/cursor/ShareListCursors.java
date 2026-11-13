package com.streamarr.server.graphql.cursor;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.services.identity.HouseholdSharesScope;
import com.streamarr.server.services.identity.ShareListScope;
import com.streamarr.server.services.pagination.ListIdentity;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The share lists that the arguments of householdShares and profileShares name, and the cursor
 * identity of each, shared by the share queries and the edges that share changes deliver. A missing
 * status list keeps every share, as an empty one does.
 */
public final class ShareListCursors {

  private ShareListCursors() {}

  public static HouseholdSharesScope householdScope(
      UUID householdId, List<ProfileShareStatus> statuses, Boolean visitorsOnly) {
    return new HouseholdSharesScope(
        new ShareListScope(householdId, statusesOrEvery(statuses)),
        Boolean.TRUE.equals(visitorsOnly));
  }

  public static ShareListScope profileScope(UUID profileId, List<ProfileShareStatus> statuses) {
    return new ShareListScope(profileId, statusesOrEvery(statuses));
  }

  public static ListIdentity listOf(HouseholdSharesScope household) {
    return new ListIdentity("household-shares", household);
  }

  public static ListIdentity listOf(ShareListScope profile) {
    return new ListIdentity("profile-shares", profile);
  }

  private static List<ProfileShareStatus> statusesOrEvery(List<ProfileShareStatus> statuses) {
    return Objects.requireNonNullElse(statuses, List.of());
  }
}
