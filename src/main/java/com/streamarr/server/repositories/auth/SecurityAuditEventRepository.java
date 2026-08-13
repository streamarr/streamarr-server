package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.SecurityAuditEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEvent, UUID> {}
