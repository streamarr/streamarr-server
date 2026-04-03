package com.streamarr.server.domain.streaming;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "session_progress")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class SessionProgress extends BaseAuditableEntity<SessionProgress> {

  private UUID sessionId;
  private UUID userId;
  private UUID mediaFileId;
  private int positionSeconds;
  private double percentComplete;
  private int durationSeconds;
}
