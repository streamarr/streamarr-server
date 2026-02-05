package com.streamarr.server.domain;

import java.time.Instant;

public class AuditFieldSetter {

  public static void setCreatedOn(BaseAuditableEntity<?> entity, Instant instant) {
    entity.setCreatedOn(instant);
  }
}
