package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import java.util.ArrayList;
import java.util.List;

public class FakeSecurityAuditEventRepository implements SecurityAuditEventRepository {

  private final List<SecurityAuditEntry> entries = new ArrayList<>();

  @Override
  public void append(SecurityAuditEntry entry) {
    entries.add(entry);
  }

  public List<SecurityAuditEntry> entries() {
    return List.copyOf(entries);
  }
}
