package com.streamarr.server.domain.auth;

import java.time.Instant;
import java.util.UUID;

/** One audit row as read for reporting; resources ride as their stored JSON text. */
public record SecurityAuditEventRecordView(
    UUID id,
    Instant occurredAt,
    UUID actorAccountId,
    String operation,
    String outcome,
    String reason,
    String resources) {}
