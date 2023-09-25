//package com.streamarr.server.services.metadata.movie;
//
//import com.streamarr.server.domain.ExternalSourceType;
//import com.streamarr.server.domain.Library;
//import com.streamarr.server.domain.external.tmdb.TmdbCredit;
//import com.streamarr.server.domain.external.tmdb.TmdbCredits;
//import com.streamarr.server.domain.external.tmdb.TmdbMovie;
//import com.streamarr.server.domain.external.tmdb.TmdbProductionCompany;
//import com.streamarr.server.domain.external.tmdb.TmdbSearchResult;
//import com.streamarr.server.domain.external.tmdb.TmdbSearchResults;
//import com.streamarr.server.fixtures.LibraryFixtureCreator;
//import com.streamarr.server.services.metadata.RemoteSearchResult;
//import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
//import com.streamarr.server.services.parsers.video.VideoFileParserResult;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.slf4j.Logger;
//
//import java.io.IOException;
//import java.net.http.HttpResponse;
//import java.util.List;
//
//import static java.util.Collections.emptyList;
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.Mockito.mock;
//import static org.mockito.Mockito.verify;
//import static org.mockito.Mockito.when;
//
//
//@DisplayName("The Movie Database Metadata Provider Tests")
//@ExtendWith(MockitoExtension.class)
//public class TMDBMovieProviderTest {
//
//    @Mock
//    private TheMovieDatabaseHttpService theMovieDatabaseHttpService;
//
//    @Mock
//    private Logger log;
//
//    @InjectMocks
//    private TMDBMovieProvider tmdbMovieProvider;
//
//    private final VideoFileParserResult fakeFileParserResult = VideoFileParserResult.builder()
//        .title("About Time")
//        .year("2013")
//        .build();
//
//
//    private final RemoteSearchResult fakeSearchResult = RemoteSearchResult.builder()
//        .externalSourceType(ExternalSourceType.TVDB)
//        .externalId("123")
//        .build();
//
//    private final Library fakeLibrary = LibraryFixtureCreator.buildFakeLibrary();
//
//    @Test
//    @DisplayName("Should make a successful search request when given Movie information.")
//    void shouldMakeSuccessfulSearchRequestWhenGivenMovieInformation() throws IOException, InterruptedException {
//        var fakeSearchResults = TmdbSearchResults.builder()
//            .results(List.of(TmdbSearchResult.builder()
//                .id(1)
//                .build()))
//            .build();
//
//        var mockResponse = buildResponse(fakeSearchResults);
//
//        when(theMovieDatabaseHttpService.searchForMovie(any(VideoFileParserResult.class))).thenReturn(mockResponse);
//
//        var result = tmdbMovieProvider.search(fakeFileParserResult);
//
//        assertThat(result).isPresent();
//    }
//
//    @Test
//    @DisplayName("Should return empty optional when no search results found for given Movie information.")
//    void shouldReturnEmptyOptionalWhenNoSearchResults() throws IOException, InterruptedException {
//        var fakeSearchResults = TmdbSearchResults.builder()
//            .results(emptyList())
//            .build();
//
//        var mockResponse = buildResponse(fakeSearchResults);
//
//        when(theMovieDatabaseHttpService.searchForMovie(any(VideoFileParserResult.class))).thenReturn(mockResponse);
//
//        var result = tmdbMovieProvider.search(fakeFileParserResult);
//
//        assertThat(result).isEmpty();
//    }
//
//
//    @Test
//    @DisplayName("Should return empty optional when exception thrown by HttpClient.")
//    void shouldReturnEmptyOptionalWhenHttpClientFailsExceptionally() throws IOException, InterruptedException {
//        when(theMovieDatabaseHttpService.searchForMovie(any(VideoFileParserResult.class))).thenThrow(new IOException());
//
//        var result = tmdbMovieProvider.search(fakeFileParserResult);
//
//        verify(log).error(anyString(), any(IOException.class));
//
//        assertThat(result).isEmpty();
//    }
//
//    @Test
//    @DisplayName("Should enrich a Movie when result returned from TMDB")
//    void shouldEnrichMovie() throws IOException, InterruptedException {
//        var fakeMovieMetadata = TmdbMovie.builder()
//            .title("About Time")
//            .productionCompanies(List.of(TmdbProductionCompany.builder()
//                .name("Universal Pictures")
//                .build()))
//            .credits(TmdbCredits.builder()
//                .cast(List.of(TmdbCredit.builder()
//                    .name("Domhnall Gleeson")
//                    .build()))
//                .build())
//            .build();
//
//        var mockResponse = buildResponse(fakeMovieMetadata);
//
//        when(theMovieDatabaseHttpService.getMovieMetadata(anyString())).thenReturn(mockResponse);
//
//        var result = tmdbMovieProvider.getMetadata(fakeSearchResult, fakeLibrary);
//
//        assertThat(result).isPresent();
//        assertThat(result.get().getTitle()).isEqualTo(fakeMovieMetadata.getTitle());
//    }
//
//    @Test
//    @DisplayName("Should fail to enrich a Movie when exception thrown by HttpClient")
//    void shouldFailToEnrichMovie() throws IOException, InterruptedException {
//        when(theMovieDatabaseHttpService.getMovieMetadata(anyString())).thenThrow(new IOException());
//
//        var result = tmdbMovieProvider.getMetadata(fakeSearchResult, fakeLibrary);
//
//        assertThat(result).isEmpty();
//    }
//
//    private <T> HttpResponse<T> buildResponse(T body) {
//        HttpResponse<T> mockResponse = mock(HttpResponse.class);
//
//        when(mockResponse.body()).thenReturn(body);
//
//        return mockResponse;
//    }
//}
