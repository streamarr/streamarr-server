package com.streamarr.server.domain.auth;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "profile")
@DynamicUpdate
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Profile extends BaseAuditableEntity<Profile> {

  private String name;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private ProfileKind kind = ProfileKind.ADULT;

  private Integer maximumAllowedRatingAge;

  private String pinHash;

  @Setter(AccessLevel.NONE)
  @Column(insertable = false, updatable = false)
  @Builder.Default
  private long managementVersion = 0;
}
