package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.SecurityAuditEntry;

public interface SecurityAuditEventRepository {

  /** Appends one winning transition inside the caller's transaction. */
  void append(SecurityAuditEntry entry);
}
