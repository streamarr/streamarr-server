package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseCollectable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
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
public class Season extends BaseCollectable<Season> {

  private int seasonNumber;

  private String overview;

  private LocalDate airDate;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "series_id")
  private Series series;

  @Builder.Default
  @OneToMany(fetch = FetchType.LAZY, mappedBy = "season")
  private List<Episode> episodes = new ArrayList<>();
}
