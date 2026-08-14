package com.streamarr.server.domain.auth;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "household")
@DynamicUpdate
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Household extends BaseAuditableEntity<Household> {

  private String name;

  // Mirrors the V044 column default.
  @Builder.Default private String defaultRatingRegion = "US";

  @Setter(AccessLevel.NONE)
  @Column(insertable = false, updatable = false)
  @Builder.Default
  private long safetyVersion = 0;
}
