package com.streamarr.server.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalIdentifier extends BaseAuditableEntity<ExternalIdentifier> {

  // UUID PK is for JPA identity; composite unique index on (external_source_type, external_id)
  // enforces business-level deduplication at the database layer.
  private UUID entityId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private ExternalSourceType externalSourceType;

  private String externalId;
}
