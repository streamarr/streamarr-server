package com.streamarr.server.domain.metadata;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Genre extends BaseAuditableEntity<Genre> {

  private String name;

  private String sourceId;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    Genre that = (Genre) o;

    return sourceId != null && sourceId.equals(that.getSourceId());
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
