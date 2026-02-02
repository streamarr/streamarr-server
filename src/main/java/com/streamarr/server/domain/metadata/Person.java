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
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Person extends BaseAuditableEntity<Person> {

  private String name;

  private String sourceId;

  private String profilePath;

  @ManyToMany(mappedBy = "cast", fetch = FetchType.LAZY)
  private Set<Movie> movies = new HashSet<>();

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    Person that = (Person) o;

    return sourceId != null && sourceId.equals(that.getSourceId());
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
