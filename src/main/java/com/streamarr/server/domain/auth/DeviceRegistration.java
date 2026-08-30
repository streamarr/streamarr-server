package com.streamarr.server.domain.auth;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A TV registered to one Household context (ADR 0024 §Devices): the winning poll creates it, and it
 * remains active while its authorizing Account may still use that Household and the ESN is
 * unblocked. Revocation keeps the row for reporting.
 */
@Entity
@Table(name = "device_registration")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegistration extends BaseAuditableEntity<DeviceRegistration> {

  private String esn;

  private String displayName;

  private UUID householdId;

  private UUID authorizingAccountId;

  private UUID authorizationId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Builder.Default
  private DeviceRegistrationStatus status = DeviceRegistrationStatus.ACTIVE;

  private Instant revokedAt;

  private UUID revokedByAccountId;

  private String revocationReason;

  private Instant lastUsedAt;
}
