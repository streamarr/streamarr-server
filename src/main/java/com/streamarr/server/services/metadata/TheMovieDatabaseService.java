package com.streamarr.server.services.metadata;

import com.streamarr.server.config.vertx.VertxWebClientProvider;
import com.streamarr.server.domain.external.tmdb.TmdbSearchResults;
import com.streamarr.server.services.extraction.video.VideoFilenameExtractionService;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.concurrent.CompletionStage;


@Service
public class TheMovieDatabaseService {

    private final VertxWebClientProvider vertxWebClientProvider;
    private final String tmdbApiKey;

    public TheMovieDatabaseService(
        VertxWebClientProvider vertxWebClientProvider,
        @Value("${tmdb.api.key:}")
        String tmdbApiKey
    ) {
        this.vertxWebClientProvider = vertxWebClientProvider;
        this.tmdbApiKey = tmdbApiKey;
    }

    public CompletionStage<HttpResponse<TmdbSearchResults>> searchForMovie(VideoFilenameExtractionService.Result result) {
        var query = new LinkedMultiValueMap<String, String>();

        query.add("query", result.title());

        if (StringUtils.isNotBlank(result.year())) {
            query.add("year", result.year());
        }

        return searchForMovieRequest(query).toCompletionStage();
    }

    private Future<HttpResponse<TmdbSearchResults>> searchForMovieRequest(MultiValueMap<String, String> query) {
        var uri = baseUrl().path("/search/movie").queryParams(query).queryParam("api_key", tmdbApiKey).build();

        return getWebClient().getAbs(uri.toString()).as(BodyCodec.json(TmdbSearchResults.class)).send();
    }

    private Future<HttpResponse<Buffer>> searchForShowRequest(MultiValueMap<String, String> query) {
        var uri = baseUrl().path("/search/tv").queryParams(query).queryParam("api_key", tmdbApiKey).build();

        return getWebClient().getAbs(uri.toString()).send();
    }

    private UriBuilder baseUrl() {
        return UriComponentsBuilder.fromHttpUrl("https://api.themoviedb.org/3");
    }

    private WebClient getWebClient() {
        return vertxWebClientProvider.createHttpClient();
    }
}
