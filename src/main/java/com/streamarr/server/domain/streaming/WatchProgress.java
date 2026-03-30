package com.streamarr.server.domain.streaming;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class WatchProgress extends BaseAuditableEntity<WatchProgress> {

  private UUID userId;
  private UUID mediaFileId;
  private int positionSeconds;
  private double percentComplete;
  private int durationSeconds;
  private Instant lastPlayedAt;

  public boolean isPlayed() {
    return lastPlayedAt != null;
  }
}
