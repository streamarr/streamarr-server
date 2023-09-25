package com.streamarr.server.domain;

import com.streamarr.server.domain.media.MediaType;
import io.hypersistence.utils.hibernate.type.basic.PostgreSQLEnumType;
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

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@TypeDef(
    name = "pgsql_enum",
    typeClass = PostgreSQLEnumType.class
)
public class Library extends BaseAuditableEntity<Library> {

    private String filepath;

    private String name;

    private Instant scanStartedOn;

    private Instant scanCompletedOn;

    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private LibraryStatus status;

    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private LibraryBackend backend;

    // Library should only contain a single type
    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private MediaType type;

    // TODO: List<Setting> property?

    // Library should use this external metadata agent strategy
    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private ExternalAgentStrategy externalAgentStrategy;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "library")
    private Set<BaseCollectable<?>> items = new HashSet<>();
}
