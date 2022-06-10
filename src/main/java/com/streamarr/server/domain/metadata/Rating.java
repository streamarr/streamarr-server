package com.streamarr.server.domain.metadata;

import com.streamarr.server.domain.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.persistence.Entity;
import java.util.UUID;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class Rating extends BaseEntity<Rating> {

    private UUID movieId;

    private String source;

    private String value;

}
