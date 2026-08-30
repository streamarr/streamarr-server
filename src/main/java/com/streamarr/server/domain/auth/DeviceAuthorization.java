package com.streamarr.server.domain.auth;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "device_authorization")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DeviceAuthorization extends BaseAuditableEntity<DeviceAuthorization> {

  private String deviceCodeDigest;

  private String userCode;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private DeviceAuthorizationStatus status;

  private String deviceName;

  /** The TV's hardware identity, presented at the code request (ADR 0024 §Devices). */
  private String esn;

  /** The Household the approver bound this TV to; null until approved. */
  private UUID chosenHouseholdId;

  private UUID decidedByAccountId;

  private Instant expiresAt;

  private Instant decidedAt;

  private Instant nextPollAt;

  private int pollIntervalSeconds;

  /** Expiry is a predicate, never a stored status: no writer has to make a row expired. */
  public boolean hasExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public boolean isPollDueAt(Instant now) {
    return !now.isBefore(nextPollAt);
  }
}
