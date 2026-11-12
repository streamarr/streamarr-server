package com.streamarr.server.repositories.notification;

import com.streamarr.server.services.events.notification.NotificationKind;
import com.streamarr.server.services.events.notification.NotificationSnapshot;
import org.jooq.JSONB;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Notification's snapshot as the document its column stores (ADR 0063 §What it stores). Its own
 * mapper keeps the stored form apart from how the API writes JSON.
 */
final class SnapshotJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private SnapshotJson() {}

  static JSONB write(NotificationSnapshot snapshot) {
    return JSONB.jsonb(MAPPER.writeValueAsString(snapshot));
  }

  static NotificationSnapshot read(NotificationKind kind, JSONB document) {
    return MAPPER.readValue(document.data(), kind.snapshotType());
  }
}
