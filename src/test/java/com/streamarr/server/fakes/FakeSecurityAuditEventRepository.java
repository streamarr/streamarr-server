package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.SecurityAuditEvent;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import java.util.List;
import java.util.UUID;

public class FakeSecurityAuditEventRepository extends FakeJpaRepository<SecurityAuditEvent>
    implements SecurityAuditEventRepository {

  @Override
  public List<SecurityAuditEvent> findByActingAccountIdAndOperation(
      UUID actingAccountId, SecurityAuditOperation operation) {
    return database.values().stream()
        .filter(event -> actingAccountId.equals(event.getActingAccountId()))
        .filter(event -> operation == event.getOperation())
        .toList();
  }
}
