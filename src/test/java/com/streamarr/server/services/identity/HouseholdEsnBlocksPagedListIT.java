package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.EsnBlock.ESN_BLOCK;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.repositories.auth.EsnBlockRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Household ESN block paging")
class HouseholdEsnBlocksPagedListIT
    extends PagedListContract<EsnBlock, HouseholdEsnBlocksPagedListIT.Scope> {

  private static final Instant NEWEST = Instant.parse("2026-10-20T12:00:00Z");

  @Autowired private DeviceAdministrationService deviceAdministration;
  @Autowired private EsnBlockRepository blocks;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport auth;

  /** A HouseholdAdmin, and the ESN behind each of its Household's blocks. */
  record Scope(AuthTestSupport.TestIdentity admin, Map<UUID, String> esnByBlock) {}

  @Override
  protected Scope createScope() {
    return new Scope(auth.createAdminIdentity(), new HashMap<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    blocks.deleteAllById(scope.esnByBlock().keySet());
    auth.deleteIdentity(scope.admin());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var block =
        blocks.saveAndFlush(
            EsnBlock.builder()
                .esn("paged-block-" + UUID.randomUUID())
                .householdId(scope.admin().household().getId())
                .reason("paging")
                .build());
    auditColumns.overwriteCreatedOn(
        ESN_BLOCK, ESN_BLOCK.ID.eq(block.getId()), NEWEST.minusSeconds(rank));
    scope.esnByBlock().put(block.getId(), block.getEsn());
    return block.getId();
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    var outcome =
        deviceAdministration.unblockEsn(
            auth.identityOf(scope.admin()),
            scope.admin().household().getId(),
            scope.esnByBlock().remove(item));
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  @Override
  protected ListPage<EsnBlock> page(Scope scope, PageRequest request) {
    return deviceAdministration.esnBlocks(
        auth.identityOf(scope.admin()), scope.admin().household().getId(), request);
  }

  @Override
  protected UUID idOf(EsnBlock block) {
    return block.getId();
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
