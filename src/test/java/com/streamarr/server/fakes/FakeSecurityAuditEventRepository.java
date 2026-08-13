package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.SecurityAuditEvent;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;

public class FakeSecurityAuditEventRepository extends FakeJpaRepository<SecurityAuditEvent>
    implements SecurityAuditEventRepository {}
