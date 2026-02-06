package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
public class Series extends BaseCollectable<Series> {

  private String backdropPath;

  private String logoPath;

  private String posterPath;

  private String tagline;

  private String summary;

  private Integer runtime;

  @Embedded
  @AttributeOverride(name = "system", column = @Column(name = "content_rating_system"))
  @AttributeOverride(name = "value", column = @Column(name = "content_rating_value"))
  @AttributeOverride(name = "country", column = @Column(name = "content_rating_country"))
  private ContentRating contentRating;

  private LocalDate firstAirDate;

  @Builder.Default
  @ManyToMany(
      cascade = {CascadeType.MERGE},
      fetch = FetchType.LAZY)
  @JoinTable(
      name = "series_company",
      joinColumns = @JoinColumn(name = "series_id"),
      inverseJoinColumns = @JoinColumn(name = "company_id"))
  private Set<Company> studios = new HashSet<>();

  @Builder.Default
  @ManyToMany(
      cascade = {CascadeType.MERGE},
      fetch = FetchType.LAZY)
  @JoinTable(
      name = "series_person",
      joinColumns = @JoinColumn(name = "series_id"),
      inverseJoinColumns = @JoinColumn(name = "person_id"))
  @OrderColumn(name = "ordinal")
  private List<Person> cast = new ArrayList<>();

  @Builder.Default
  @ManyToMany(
      cascade = {CascadeType.MERGE},
      fetch = FetchType.LAZY)
  @JoinTable(
      name = "series_director",
      joinColumns = @JoinColumn(name = "series_id"),
      inverseJoinColumns = @JoinColumn(name = "person_id"))
  @OrderColumn(name = "ordinal")
  private List<Person> directors = new ArrayList<>();

  @Builder.Default
  @ManyToMany(
      cascade = {CascadeType.MERGE},
      fetch = FetchType.LAZY)
  @JoinTable(
      name = "series_genre",
      joinColumns = @JoinColumn(name = "series_id"),
      inverseJoinColumns = @JoinColumn(name = "genre_id"))
  private Set<Genre> genres = new HashSet<>();

  @Builder.Default
  @OneToMany(fetch = FetchType.LAZY, mappedBy = "series")
  private List<Season> seasons = new ArrayList<>();

  public void addPersonToCast(Person person) {
    cast.add(person);
  }
}
