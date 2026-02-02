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
public class TmdbExternalIds {

    @JsonProperty("imdb_id")
    private String imdbId;
    @JsonProperty("facebook_id")
    private String facebookId;
    @JsonProperty("instagram_id")
    private String instagramId;
    @JsonProperty("twitter_id")
    private String twitterId;
}
