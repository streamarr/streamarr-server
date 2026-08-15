package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.SecurityAuditEvent;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import java.util.List;
import java.util.UUID;

public class FakeSecurityAuditEventRepository extends FakeJpaRepository<SecurityAuditEvent>
    implements SecurityAuditEventRepository {

  /**
   * Finds security audit events for an acting account and operation.
   *
   * @param actingAccountId the acting account identifier
   * @param operation       the audit operation
   * @return the matching security audit events
   */
  @Override
  public List<SecurityAuditEvent> findByActingAccountIdAndOperation(
      UUID actingAccountId, SecurityAuditOperation operation) {
    return database.values().stream()
        .filter(event -> actingAccountId.equals(event.getActingAccountId()))
        .filter(event -> operation == event.getOperation())
        .toList();
  }
}
