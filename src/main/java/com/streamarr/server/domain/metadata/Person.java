package com.streamarr.server.domain.metadata;

import com.streamarr.server.domain.BaseEntity;
import com.streamarr.server.domain.media.Movie;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ManyToMany;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class Person extends BaseEntity<Person> {

    private String name;

    @ManyToMany(mappedBy = "cast", fetch = FetchType.LAZY)
    private Set<Movie> movies = new HashSet<>();
}
