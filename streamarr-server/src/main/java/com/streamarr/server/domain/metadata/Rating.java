package com.streamarr.server.domain.metadata;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.domain.media.Movie;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Rating extends BaseAuditableEntity<Rating> {

  @ManyToOne
  @JoinColumn(name = "movieId")
  private Movie movie;

  private String source;

  private String value;
}
