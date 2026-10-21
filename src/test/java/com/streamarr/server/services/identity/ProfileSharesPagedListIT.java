package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
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
@DisplayName("Profile share paging")
class ProfileSharesPagedListIT
    extends PagedListContract<ProfileHouseholdShare, ProfileSharesPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);
  private static final Instant NEWEST = Instant.parse("2001-01-01T12:00:00Z");

  @Autowired private ProfileSharingService profileSharing;
  @Autowired private ProfileHouseholdShareRepository shares;
  @Autowired private HouseholdRepository households;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport auth;

  /**
   * An Account reading its Personal Profile's shares, and the Households that Profile was offered.
   * The Profile's structural share into its own Household is created now, long after the arranged
   * shares, so it sorts before all of them.
   */
  record Scope(AuthTestSupport.TestIdentity owner, List<UUID> hosts) {}

  @Override
  protected Scope createScope() {
    return new Scope(auth.createIdentity(), new ArrayList<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    auth.deleteIdentity(scope.owner());
    households.deleteAllById(scope.hosts());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var host = households.saveAndFlush(Household.builder().name("Paged host").build());
    scope.hosts().add(host.getId());
    var offer =
        shares.saveAndFlush(
            ProfileHouseholdShare.builder()
                .profileId(scope.owner().profile().getId())
                .householdId(host.getId())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(scope.owner().account().getId())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
    auditColumns.overwriteCreatedOn(
        PROFILE_HOUSEHOLD_SHARE,
        PROFILE_HOUSEHOLD_SHARE.ID.eq(offer.getId()),
        NEWEST.minusSeconds(rank));
    return offer.getId();
  }

  // The product keeps every share of a Profile; deleting the row still proves a page continues.
  @Override
  protected void remove(Scope scope, UUID item) {
    shares.deleteById(item);
    shares.flush();
  }

  @Override
  protected ListPage<ProfileHouseholdShare> page(Scope scope, PageRequest request) {
    return profileSharing.profileShares(
        auth.identityOf(scope.owner()), scope.owner().profile().getId(), request);
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
    return Optional.of(new Position(new SortValue.OfInstant(NEWEST.plusSeconds(1)), LOWEST_ID));
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.of(new Position(new SortValue.OfInstant(NEWEST.minusSeconds(10)), LOWEST_ID));
  }
}
