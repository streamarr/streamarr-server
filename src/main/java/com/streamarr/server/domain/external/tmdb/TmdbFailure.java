package com.streamarr.server.domain.external.tmdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TmdbFailure {

    @JsonProperty("status_message")
    private String statusMessage;
    private boolean success;
    @JsonProperty("status_code")
    private int statusCode;
}
