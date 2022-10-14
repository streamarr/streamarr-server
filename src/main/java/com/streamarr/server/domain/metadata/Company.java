package com.streamarr.server.domain.metadata;

import com.streamarr.server.domain.BaseAuditableEntity;
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
@NoArgsConstructor
@AllArgsConstructor
public class Company extends BaseAuditableEntity<Company> {

    private String name;

    private String sourceId;

    @ManyToMany(mappedBy = "studios", fetch = FetchType.LAZY)
    private Set<Movie> movies = new HashSet<>();
}
