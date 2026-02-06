package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseCollectable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.LocalDate;
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
public class Episode extends BaseCollectable<Episode> {

  private int episodeNumber;

  private String overview;

  private String stillPath;

  private LocalDate airDate;

  private Integer runtime;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "season_id")
  private Season season;
}
