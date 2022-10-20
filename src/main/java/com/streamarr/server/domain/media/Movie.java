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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class Movie extends BaseCollectable<Movie> {

    // TODO: Should this actually be imdbId or "externalId"?
    private String tmdbId;

    private String artwork;

    // TODO: ENUM or String? Example: "PG", "R"
    private String contentRating;

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
        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
        fetch = FetchType.LAZY)
    @JoinTable(
        name = "movie_person",
        joinColumns = @JoinColumn(name = "movie_id"),
        inverseJoinColumns = @JoinColumn(name = "person_id"))
    private Set<Person> cast = new HashSet<>();

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "movieId")
    private Set<Rating> ratings = new HashSet<>();

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "movieId")
    private Set<Review> reviews = new HashSet<>();

    public void addPersonToCast(Person person) {
        cast.add(person);
    }
}
