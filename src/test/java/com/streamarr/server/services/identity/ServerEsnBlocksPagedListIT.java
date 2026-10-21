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
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Server-wide ESN block paging")
class ServerEsnBlocksPagedListIT
    extends PagedListContract<EsnBlock, ServerEsnBlocksPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);
  private static final Instant EARLIEST_WINDOW = Instant.parse("1990-01-01T00:00:00Z");

  @Autowired private DeviceAdministrationService deviceAdministration;
  @Autowired private EsnBlockRepository blocks;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport auth;

  /**
   * A ServerAdmin, the ESN behind each arranged block, and a creation time no other test's blocks
   * share: rank 0 is created then, and each higher rank one second earlier.
   */
  record Scope(
      AuthTestSupport.TestIdentity admin, Instant newestCreatedOn, Map<UUID, String> esnByBlock) {}

  @Override
  protected Scope createScope() {
    var newest =
        EARLIEST_WINDOW.plus(
            ThreadLocalRandom.current().nextLong(1, 10L * 365 * 24 * 60 * 60), ChronoUnit.SECONDS);
    return new Scope(auth.createAdminIdentity(), newest, new HashMap<>());
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
            EsnBlock.builder().esn("paged-block-" + UUID.randomUUID()).reason("paging").build());
    auditColumns.overwriteCreatedOn(
        ESN_BLOCK, ESN_BLOCK.ID.eq(block.getId()), scope.newestCreatedOn().minusSeconds(rank));
    scope.esnByBlock().put(block.getId(), block.getEsn());
    return block.getId();
  }

  @Override
  protected void remove(Scope scope, UUID item) {
    var outcome =
        deviceAdministration.unblockEsnServerWide(
            auth.identityOf(scope.admin()), scope.esnByBlock().remove(item));
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  @Override
  protected ListPage<EsnBlock> page(Scope scope, PageRequest request) {
    return deviceAdministration.serverEsnBlocks(auth.identityOf(scope.admin()), request);
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
    return Optional.of(
        new Position(new SortValue.OfInstant(scope.newestCreatedOn().plusSeconds(1)), LOWEST_ID));
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.of(
        new Position(new SortValue.OfInstant(scope.newestCreatedOn().minusSeconds(10)), LOWEST_ID));
  }
}
