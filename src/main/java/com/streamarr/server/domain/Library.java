package com.streamarr.server.domain;

import com.streamarr.server.domain.media.MediaType;
import com.vladmihalcea.hibernate.type.basic.PostgreSQLEnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.hibernate.annotations.TypeDefs;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@TypeDefs({
    @TypeDef(
        name = "pgsql_enum",
        typeClass = PostgreSQLEnumType.class
    )
})
public class Library extends BaseEntity<Library> {

    private String filepath;
    private String name;

    private Instant refreshStartedOn;
    private Instant refreshCompletedOn;

    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private LibraryStatus status;

    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private LibraryBackend backend;

    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private MediaType type;

    // Library should only contain a single type
    // TODO: Relay spec pagination
    @OneToMany(fetch = FetchType.LAZY, mappedBy = "libraryId")
    private Set<BaseCollectable> items = new HashSet<>();

}
