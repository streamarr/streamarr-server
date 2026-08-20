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
 * sharedInto: makes a Profile available in a Household while ACTIVE. The structural share of a
 * Personal Profile into its Account's Household is created with the Account and cannot end while
 * the Account remains a member.
 */
@Entity
@Table(name = "profile_household_share")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ProfileHouseholdShare extends BaseAuditableEntity<ProfileHouseholdShare> {

  private UUID profileId;

  private UUID householdId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private ProfileShareStatus status;

  @Builder.Default private boolean structural = false;

  private UUID offeredByAccountId;

  private Instant expiresAt;

  private Instant decidedAt;

  private Instant endedAt;

  private String invalidationReason;
}
