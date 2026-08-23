package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.SecurityAuditEntry;

public interface SecurityAuditEventRepository {

  void append(SecurityAuditEntry entry);
}
