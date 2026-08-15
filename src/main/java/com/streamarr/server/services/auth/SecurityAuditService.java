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

  /**
   * Records a security audit event.
   *
   * @param auditRecord the audit record to persist
   * @return the persisted security audit event
   */
  public SecurityAuditEvent recordEvent(SecurityAuditRecord auditRecord) {
    return repository.save(event(auditRecord));
  }

  /**
   * Records a security audit event in a new transaction and immediately flushes it.
   *
   * @param auditRecord the security audit details to persist
   * @return the persisted security audit event
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SecurityAuditEvent recordFailure(SecurityAuditRecord auditRecord) {
    return repository.saveAndFlush(event(auditRecord));
  }

  /**
   * Creates a security audit event from the supplied audit record.
   *
   * @param auditRecord the record containing the audit event details
   * @return the corresponding security audit event
   */
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
