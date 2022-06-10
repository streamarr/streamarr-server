package com.streamarr.server.graphql.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RatingInput {

    private String userId;
    private String source;
    private String value;

}
