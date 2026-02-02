package com.streamarr.server.domain;

import com.streamarr.server.domain.media.MediaType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
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
public class Library extends BaseAuditableEntity<Library> {

  private String filepath;

  private String name;

  private Instant scanStartedOn;

  private Instant scanCompletedOn;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private LibraryStatus status;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private LibraryBackend backend;

  // Library should only contain a single type
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private MediaType type;

  // Library should use this external metadata agent strategy
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private ExternalAgentStrategy externalAgentStrategy;

  @OneToMany(fetch = FetchType.LAZY, mappedBy = "library")
  private Set<BaseCollectable<?>> items = new HashSet<>();
}
