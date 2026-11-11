package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.ShareCode.SHARE_CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The live share codes of a Household, paged through its codes kept to LIVE: withdrawing a code
 * takes it out of the list.
 */
@Tag("IntegrationTest")
@DisplayName("Household share code paging")
class HouseholdShareCodesPagedListIT
    extends PagedListContract<ShareCodeWithIssuer, AuthTestSupport.TestIdentity> {

  private static final Instant NEWEST = Instant.parse("2026-10-20T12:00:00Z");

  @Autowired private ShareCodeService shareCodeService;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport auth;

  @Override
  protected AuthTestSupport.TestIdentity createScope() {
    return auth.createIdentity();
  }

  @Override
  protected void deleteScope(AuthTestSupport.TestIdentity host) {
    auth.deleteIdentity(host);
  }

  @Override
  protected UUID add(AuthTestSupport.TestIdentity host, int rank) {
    var issued = shareCodeService.issueShareCode(auth.identityOf(host), host.household().getId());
    var id =
        issued.fold(
            created -> created.shareCode().shareCode().getId(),
            rejections -> {
              throw new AssertionError("rejected: " + rejections);
            });
    auditColumns.overwriteCreatedOn(SHARE_CODE, SHARE_CODE.ID.eq(id), NEWEST.minusSeconds(rank));
    return id;
  }

  @Override
  protected void remove(AuthTestSupport.TestIdentity host, UUID item) {
    var outcome = shareCodeService.withdrawShareCode(auth.identityOf(host), item);
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  @Override
  protected ListPage<ShareCodeWithIssuer> page(
      AuthTestSupport.TestIdentity host, PageRequest request) {
    return shareCodeService.householdShareCodes(
        auth.identityOf(host),
        new HouseholdShareCodesScope(host.household().getId(), List.of(ShareCodeStatus.LIVE)),
        request);
  }

  @Override
  protected UUID idOf(ShareCodeWithIssuer listed) {
    return listed.shareCode().getId();
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
  protected Optional<Position> positionBeforeArrangedItems(AuthTestSupport.TestIdentity host) {
    return Optional.empty();
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(AuthTestSupport.TestIdentity host) {
    return Optional.empty();
  }
}
