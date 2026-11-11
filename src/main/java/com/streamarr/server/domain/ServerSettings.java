package com.streamarr.server.domain;

import com.streamarr.server.domain.streaming.EncoderPresetChoice;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.DynamicUpdate;

/**
 * The server-wide settings. The table holds exactly one row, which its migration creates. Each
 * update writes only the columns it changed, so changes to different settings never undo each
 * other.
 */
@Entity
@DynamicUpdate
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ServerSettings extends BaseAuditableEntity<ServerSettings> {

  @Enumerated(EnumType.STRING)
  @Builder.Default
  private EncoderPresetChoice encoderPreset = EncoderPresetChoice.AUTOMATIC;

  @Builder.Default private int idleSegmentRetentionMinutes = 60;

  @Builder.Default private int notificationRetentionDays = 90;

  @Builder.Default private int shareCodeLifetimeHours = 168;

  @Builder.Default private int invitationLifetimeHours = 168;
}
