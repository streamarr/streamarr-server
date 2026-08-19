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
@Table(name = "auth_session")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AuthSession extends BaseAuditableEntity<AuthSession> {

  private UUID accountId;

  private String deviceName;

  /** The one Household this session is using: membership by default, or a visited Household. */
  private UUID contextHouseholdId;

  /** The Profile selected through select-profile; null in Account scope. */
  private UUID selectedProfileId;

  private Instant revokedAt;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private SessionRevocationReason revokedReason;

  private Instant lastUsedAt;
}
