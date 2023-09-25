package com.streamarr.server.domain;


import io.hypersistence.utils.hibernate.type.basic.PostgreSQLEnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;

import java.util.UUID;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalIdentifier extends BaseAuditableEntity<ExternalIdentifier> {

    // The parent
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Type(PostgreSQLEnumType.class)
    private ExternalSourceType externalSourceType;

    private String externalId;
}
