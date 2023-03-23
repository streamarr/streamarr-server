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
public class Person extends BaseAuditableEntity<Person> {

    private String name;

    private String sourceId;

    @ManyToMany(mappedBy = "cast", fetch = FetchType.LAZY)
    private Set<Movie> movies = new HashSet<>();


    // TODO: Do we want to use this?
//    @Builder.Default
//    @OneToMany(
//        cascade = {CascadeType.PERSIST, CascadeType.MERGE},
//        fetch = FetchType.LAZY,
//        mappedBy = "entityId")
//    @Setter(AccessLevel.NONE)
//    private final Set<ExternalIdentifier> externalIds = new HashSet<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Person that = (Person) o;

        return name != null && name.equals(that.getName());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
