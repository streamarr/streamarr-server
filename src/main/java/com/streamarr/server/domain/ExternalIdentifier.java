package com.streamarr.server.domain;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;

import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
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
    @Type(type = "pgsql_enum")
    private ExternalSourceType externalSourceType;

    private String externalId;
}
