package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.domain.metadata.Review;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Movie extends BaseCollectable<Movie> {

    private String backdropPath;

    private String posterPath;

    private String tagline;

    private String summary;

    // TODO: ENUM or String? Example: "PG", "R". Or should this be modeled differently...?
    private String contentRating;

    private LocalDate releaseDate;

    @Builder.Default
    @ManyToMany(
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    @JoinTable(
        name = "movie_company",
        joinColumns = @JoinColumn(name = "movie_id"),
        inverseJoinColumns = @JoinColumn(name = "company_id"))
    private Set<Company> studios = new HashSet<>();

    @Builder.Default
    @ManyToMany(
        cascade = {CascadeType.MERGE},
        fetch = FetchType.LAZY)
    @JoinTable(
        name = "movie_person",
        joinColumns = @JoinColumn(name = "movie_id"),
        inverseJoinColumns = @JoinColumn(name = "person_id"))
    // TODO: Use cast ordering?
    @OrderBy("name")
    private Set<Person> cast = new LinkedHashSet<>();

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "movie")
    private Set<Rating> ratings = new HashSet<>();

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "movie")
    private Set<Review> reviews = new HashSet<>();

    public void addPersonToCast(Person person) {
        cast.add(person);
    }
}
