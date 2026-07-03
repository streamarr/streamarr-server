package com.streamarr.server.domain.streaming;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "watch_history")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class WatchHistory extends BaseAuditableEntity<WatchHistory> {

  private UUID userId;
  private UUID collectableId;
  private Instant watchedAt;
  private int durationSeconds;
  private Instant dismissedAt;
}
