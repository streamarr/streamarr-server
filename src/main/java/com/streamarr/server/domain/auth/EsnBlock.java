package com.streamarr.server.domain.auth;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A refused ESN (ADR 0024 §Devices): scoped to one Household, or server-wide when householdId is
 * null. T10 guarantees a block admits no active registration or refreshable device session.
 */
@Entity
@Table(name = "esn_block")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class EsnBlock extends BaseAuditableEntity<EsnBlock> {

  private String esn;

  /** Null scopes the block server-wide. */
  private UUID householdId;

  private String reason;
}
