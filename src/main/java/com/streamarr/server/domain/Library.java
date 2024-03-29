package com.streamarr.server.domain;

import com.streamarr.server.domain.media.MediaType;
import com.vladmihalcea.hibernate.type.basic.PostgreSQLEnumType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.OneToMany;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@TypeDef(
    name = "pgsql_enum",
    typeClass = PostgreSQLEnumType.class
)
public class Library extends BaseEntity<Library> {

    private String filepath;

    private String name;

    private Instant refreshStartedOn;

    private Instant refreshCompletedOn;

    @Enumerated(EnumType.STRING)
    @Type(type = "pgsql_enum")
    private LibraryStatus status;

    @Enumerated(EnumType.STRING)
    @Type(type = "pgsql_enum")
    private LibraryBackend backend;

    // Library should only contain a single type
    @Enumerated(EnumType.STRING)
    @Type(type = "pgsql_enum")
    private MediaType type;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "libraryId")
    private Set<BaseCollectable<?>> items = new HashSet<>();
}
