package com.streamarr.server.domain.metadata;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.domain.media.Movie;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Company extends BaseAuditableEntity<Company> {

  private String name;

  private String sourceId;

  private String logoPath;

  @ManyToMany(mappedBy = "studios", fetch = FetchType.LAZY)
  private Set<Movie> movies = new HashSet<>();

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    Company that = (Company) o;

    return sourceId != null && sourceId.equals(that.getSourceId());
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
