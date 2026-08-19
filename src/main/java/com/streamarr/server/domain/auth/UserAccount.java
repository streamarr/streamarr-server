package com.streamarr.server.domain.auth;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
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
 * The login and security principal (ADR 0024): one membership Household with a role, optional
 * ServerAdmin authority, and exactly one Personal Profile. Authority is never read from this entity
 * by policy — the authorization module loads live facts with scalar queries.
 */
@Entity
@Table(name = "user_account")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class UserAccount extends BaseAuditableEntity<UserAccount> {

  private String email;

  private String displayName;

  private String passwordHash;

  @Builder.Default private boolean serverAdmin = false;

  /** memberOf: the Account's one Household. */
  private UUID householdId;

  /** adminOf when ADMIN; implies membership. */
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Builder.Default
  private HouseholdRole householdRole = HouseholdRole.MEMBER;

  /** personalProfileOf: the one Profile that represents this Account. */
  private UUID personalProfileId;

  // Mirrors the V044 column default; disabled-by-default flows (invites, verification)
  // must opt out explicitly.
  @Builder.Default private boolean enabled = true;
}
