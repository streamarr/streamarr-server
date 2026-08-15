package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SecurityAuditEvent;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SecurityAuditService {

  private final SecurityAuditEventRepository repository;

  public SecurityAuditEvent recordEvent(SecurityAuditRecord auditRecord) {
    return repository.save(event(auditRecord));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SecurityAuditEvent recordFailure(SecurityAuditRecord auditRecord) {
    return repository.saveAndFlush(event(auditRecord));
  }

  private SecurityAuditEvent event(SecurityAuditRecord auditRecord) {
    return SecurityAuditEvent.builder()
        .actingAccountId(auditRecord.actingAccountId())
        .targetAccountId(auditRecord.targetAccountId())
        .targetHouseholdId(auditRecord.targetHouseholdId())
        .targetProfileId(auditRecord.targetProfileId())
        .operation(auditRecord.operation())
        .reason(auditRecord.reason())
        .build();
  }
}
