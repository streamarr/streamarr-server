package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;

import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PagedListContract;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Security audit paging")
class SecurityAuditPagedListIT
    extends PagedListContract<SecurityAuditEventRecordView, SecurityAuditPagedListIT.Scope> {

  private static final UUID LOWEST_ID = new UUID(0, 0);
  private static final Instant EARLIEST_WINDOW = Instant.parse("1990-01-01T00:00:00Z");

  @Autowired private HouseholdDeletionService householdDeletion;
  @Autowired private DSLContext dsl;
  @Autowired private AuthTestSupport auth;

  /**
   * A ServerAdmin, the arranged audit events, and a time no other test's events share: rank 0
   * occurred then, and each higher rank one second earlier.
   */
  record Scope(AuthTestSupport.TestIdentity admin, Instant newestOccurredAt, List<UUID> events) {}

  @Override
  protected Scope createScope() {
    var newest =
        EARLIEST_WINDOW.plus(
            ThreadLocalRandom.current().nextLong(1, 10L * 365 * 24 * 60 * 60), ChronoUnit.SECONDS);
    return new Scope(auth.createAdminIdentity(), newest, new ArrayList<>());
  }

  @Override
  protected void deleteScope(Scope scope) {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT)
        .where(SECURITY_AUDIT_EVENT.ID.in(scope.events()))
        .execute();
    auth.deleteIdentity(scope.admin());
  }

  @Override
  protected UUID add(Scope scope, int rank) {
    var id = UUID.randomUUID();
    dsl.insertInto(SECURITY_AUDIT_EVENT)
        .set(SECURITY_AUDIT_EVENT.ID, id)
        .set(
            SECURITY_AUDIT_EVENT.OCCURRED_AT,
            scope.newestOccurredAt().minusSeconds(rank).atOffset(ZoneOffset.UTC))
        .set(SECURITY_AUDIT_EVENT.ACTOR_ACCOUNT_ID, scope.admin().account().getId())
        .set(SECURITY_AUDIT_EVENT.OPERATION, "pagedAuditEvent")
        .set(SECURITY_AUDIT_EVENT.OUTCOME, "SUCCESS")
        .set(SECURITY_AUDIT_EVENT.RESOURCES, JSONB.jsonb("{}"))
        .execute();
    scope.events().add(id);
    return id;
  }

  // The product never removes an audit event; deleting the row still proves a page continues.
  @Override
  protected void remove(Scope scope, UUID item) {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).where(SECURITY_AUDIT_EVENT.ID.eq(item)).execute();
  }

  @Override
  protected ListPage<SecurityAuditEventRecordView> page(Scope scope, PageRequest request) {
    return householdDeletion.securityAuditEvents(auth.identityOf(scope.admin()), request);
  }

  @Override
  protected UUID idOf(SecurityAuditEventRecordView event) {
    return event.id();
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
        new Position(new SortValue.OfInstant(scope.newestOccurredAt().plusSeconds(1)), LOWEST_ID));
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Scope scope) {
    return Optional.of(
        new Position(
            new SortValue.OfInstant(scope.newestOccurredAt().minusSeconds(10)), LOWEST_ID));
  }
}
