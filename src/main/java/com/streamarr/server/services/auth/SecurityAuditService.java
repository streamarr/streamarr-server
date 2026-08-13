package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SecurityAuditEvent;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SecurityAuditService {

  private final SecurityAuditEventRepository repository;

  public SecurityAuditEvent record(SecurityAuditRecord record) {
    return repository.save(
        SecurityAuditEvent.builder()
            .actingAccountId(record.actingAccountId())
            .targetAccountId(record.targetAccountId())
            .targetHouseholdId(record.targetHouseholdId())
            .targetProfileId(record.targetProfileId())
            .operation(record.operation())
            .reason(record.reason())
            .build());
  }
}
