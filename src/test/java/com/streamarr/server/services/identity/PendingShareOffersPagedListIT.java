package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Pending share offer paging")
class PendingShareOffersPagedListIT
    extends PagedListContract<ProfileHouseholdShare, PendingShareOffersPagedListIT.Scope> {

  private static final Instant NEWEST = Instant.parse("2026-10-20T12:00:00Z");

  @Autowired private ProfileSharingService profileSharing;
  @Autowired private ProfileHouseholdShareRepository shares;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport auth;

  /** A host HouseholdAdmin, and the Accounts whose Personal Profiles it was offered. */
  record Scope(AuthTestSupport.TestIdentity host, List<AuthTestSupport.TestIdentity> owners) {}

  @Override
  protected Scope createScope() {
    return new Scope(auth.createIdentity(), new ArrayList<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    scope.owners().forEach(auth::deleteIdentity);
    auth.deleteIdentity(scope.host());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var owner = auth.createIdentity();
    scope.owners().add(owner);
    var offer =
        shares.saveAndFlush(
            ProfileHouseholdShare.builder()
                .profileId(owner.profile().getId())
                .householdId(scope.host().household().getId())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(owner.account().getId())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
    auditColumns.overwriteCreatedOn(
        PROFILE_HOUSEHOLD_SHARE,
        PROFILE_HOUSEHOLD_SHARE.ID.eq(offer.getId()),
        NEWEST.minusSeconds(rank));
    return offer.getId();
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    var outcome = profileSharing.rejectProfileShare(auth.freshIdentityOf(scope.host()), item);
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  @Override
  protected ListPage<ProfileHouseholdShare> page(Scope scope, PageRequest request) {
    return profileSharing.pendingShareOffers(
        auth.identityOf(scope.host()), scope.host().household().getId(), request);
  }

  @Override
  protected UUID idOf(ProfileHouseholdShare share) {
    return share.getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.DESC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return true;
  }

  @Override
  protected Optional<Position> positionBeforeArrangedItems(Scope scope) {
    return Optional.empty();
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.empty();
  }
}
