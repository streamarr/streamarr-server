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

/** The server-wide settings. The table holds exactly one row, which its migration creates. */
@Entity
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ServerSettings extends BaseAuditableEntity<ServerSettings> {

  @Enumerated(EnumType.STRING)
  @Builder.Default
  private EncoderPresetChoice encoderPreset = EncoderPresetChoice.AUTOMATIC;
}
