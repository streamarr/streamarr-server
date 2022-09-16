package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.domain.metadata.Review;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
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

    // TODO: Should this actually be imdbId?
    private String tmdbId;

    // TODO: Store these locally? What about the intermediate state when we only have a URL?
    private String artwork;

    // TODO: ENUM or String? Example: "PG", "R"
    private String contentRating;

    // TODO: Should the below be inside a Metadata object?
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "movie_company",
        joinColumns = @JoinColumn(name = "movie_id"),
        inverseJoinColumns = @JoinColumn(name = "company_id"))
    private Set<Company> studios = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "movie_person",
        joinColumns = @JoinColumn(name = "movie_id"),
        inverseJoinColumns = @JoinColumn(name = "person_id"))
    // TODO: Question: Can a person appear more than once? Voice for 2 characters? Is a Set applicable?
    private Set<Person> cast = new HashSet<>();

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "movieId")
    private Set<Rating> ratings = new HashSet<>();

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "movieId")
    private Set<Review> reviews = new HashSet<>();
}
