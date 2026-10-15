package com.streamarr.server.domain;

import java.time.Instant;
import java.util.UUID;

public class AuditFieldSetter {

  public static void setCreatedBy(BaseAuditableEntity<?> entity, UUID accountId) {
    entity.setCreatedBy(accountId);
  }

  public static void setCreatedOn(BaseAuditableEntity<?> entity, Instant instant) {
    entity.setCreatedOn(instant);
  }

  public static void setLastModifiedOn(BaseAuditableEntity<?> entity, Instant instant) {
    entity.setLastModifiedOn(instant);
  }
}
