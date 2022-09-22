package com.streamarr.server.services;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.OrderMoviesBy;
import com.streamarr.server.graphql.cursor.PaginationDirection;
import com.streamarr.server.graphql.cursor.PaginationOptions;
import com.streamarr.server.repositories.media.MovieRepository;
import graphql.relay.DefaultConnectionCursor;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.jooq.SortOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("UnitTest")
@ExtendWith(MockitoExtension.class)
@DisplayName("Movie Service Tests")
public class MovieServiceTest {

    @Mock
    private MovieRepository mockMovieRepository;
    @Mock
    private CursorUtil mockCursorUtil;
    @Mock
    private RelayPaginationService mockRelayPaginationService;

    @InjectMocks
    private MovieService movieService;

    private final PaginationOptions fakePaginationOptionsWithoutCursor = PaginationOptions.builder()
        .paginationDirection(PaginationDirection.FORWARD)
        .limit(1)
        .cursor(Optional.empty())
        .build();

    private final PaginationOptions fakePaginationOptionsWithCursor = fakePaginationOptionsWithoutCursor.toBuilder()
        .cursor(Optional.of("cursor-placeholder"))
        .build();

    private final List<Movie> fakeMovies = List.of(Movie.builder()
        .title("About Time")
        .build());


    @Test
    @DisplayName("Should query database once using default filter when given no filter and no cursor")
    void shouldQueryDatabaseOnceUsingDefaultFilterWhenNoFilterOrCursor() {
        when(mockRelayPaginationService.getPaginationOptions(eq(fakePaginationOptionsWithoutCursor.getLimit()), eq(null), anyInt(), nullable(String.class))).thenReturn(fakePaginationOptionsWithoutCursor);

        var captor = ArgumentCaptor.forClass(MediaPaginationOptions.class);

        movieService.getMoviesWithFilter(1, null, 0, null, null);

        verify(mockMovieRepository, times(1)).findFirstWithFilter(captor.capture());

        var capturedFilter = captor.getValue().getMediaFilter();

        assertThat(capturedFilter.getSortBy()).isEqualTo(OrderMoviesBy.TITLE);
        assertThat(capturedFilter.getSortDirection()).isEqualTo(SortOrder.ASC);
        assertThat(capturedFilter.getPreviousSortFieldValue()).isNull();
    }

    @Test
    @DisplayName("Should query database once using provided filter when given filter without cursor")
    void shouldQueryDatabaseOnceUsingProvidedFilterWhenGivenFilterAndNoCursor() {
        var fakeFilter = MediaFilter.builder()
            .sortBy(OrderMoviesBy.ADDED)
            .sortDirection(SortOrder.DESC)
            .build();

        when(mockRelayPaginationService.getPaginationOptions(eq(fakePaginationOptionsWithoutCursor.getLimit()), eq(null), anyInt(), nullable(String.class))).thenReturn(fakePaginationOptionsWithoutCursor);

        var captor = ArgumentCaptor.forClass(MediaPaginationOptions.class);

        movieService.getMoviesWithFilter(1, null, 0, null, fakeFilter);

        verify(mockMovieRepository, times(1)).findFirstWithFilter(captor.capture());

        var capturedFilter = captor.getValue().getMediaFilter();

        assertThat(capturedFilter.getSortBy()).isEqualTo(fakeFilter.getSortBy());
        assertThat(capturedFilter.getSortDirection()).isEqualTo(fakeFilter.getSortDirection());
        assertThat(capturedFilter.getPreviousSortFieldValue()).isNull();
    }

    @Test
    @DisplayName("Should query database once using decoded cursor and previous sort value when given cursor")
    void shouldQueryDatabaseOnceUsingCursorWithPreviousSortValueWhenGivenCursor() {
        var fakeOptionsWithPreviousValue = MediaPaginationOptions.builder()
            .mediaFilter(MediaFilter.builder()
                .sortBy(OrderMoviesBy.TITLE)
                .sortDirection(SortOrder.ASC)
                .previousSortFieldValue("About Time")
                .build())
            .paginationOptions(fakePaginationOptionsWithCursor)
            .build();

        var fakeCursor = fakePaginationOptionsWithCursor.getCursor().orElseThrow();

        when(mockRelayPaginationService.getPaginationOptions(eq(fakePaginationOptionsWithCursor.getLimit()), eq(fakeCursor), anyInt(), nullable(String.class))).thenReturn(fakePaginationOptionsWithCursor);
        when(mockCursorUtil.decodeMediaCursor(any(PaginationOptions.class))).thenReturn(fakeOptionsWithPreviousValue);

        var captor = ArgumentCaptor.forClass(MediaPaginationOptions.class);

        movieService.getMoviesWithFilter(1, fakeCursor, 0, null, null);

        verify(mockMovieRepository, times(1)).seekWithFilter(captor.capture());

        var capturedFilter = captor.getValue().getMediaFilter();

        assertThat(capturedFilter.getPreviousSortFieldValue()).isEqualTo(fakeOptionsWithPreviousValue.getMediaFilter().getPreviousSortFieldValue());
    }

    @Test
    @DisplayName("Should encode cursor containing movie 'title' sort value when given default media filter")
    void shouldEncodeCursorContainingTitleSortValueWhenGivenDefaultMediaFilter() {
        when(mockRelayPaginationService.getPaginationOptions(eq(fakePaginationOptionsWithoutCursor.getLimit()), eq(null), anyInt(), nullable(String.class))).thenReturn(fakePaginationOptionsWithoutCursor);
        when(mockMovieRepository.findFirstWithFilter(any(MediaPaginationOptions.class))).thenReturn(fakeMovies);
        when(mockCursorUtil.encodeMediaCursor(any(MediaPaginationOptions.class), nullable(UUID.class), any())).thenReturn(new DefaultConnectionCursor("new-cursor"));

        var captor = ArgumentCaptor.forClass(Object.class);

        movieService.getMoviesWithFilter(1, null, 0, null, null);

        verify(mockCursorUtil, times(1)).encodeMediaCursor(any(MediaPaginationOptions.class), nullable(UUID.class), captor.capture());

        String currentSortValue = (String) captor.getValue();

        assertThat(currentSortValue).isEqualTo(fakeMovies.get(0).getTitle());
    }

    @Test
    @DisplayName("Should encode cursor containing movie 'createdOn' sort value when given media filter")
    void shouldEncodeCursorContainingCreatedOnSortValueWhenGivenMediaFilter() throws IllegalAccessException {
        FieldUtils.writeField(fakeMovies.get(0), "createdOn", Instant.now(), true);

        var fakeFilter = MediaFilter.builder()
            .sortBy(OrderMoviesBy.ADDED)
            .sortDirection(SortOrder.DESC)
            .build();

        when(mockRelayPaginationService.getPaginationOptions(eq(fakePaginationOptionsWithoutCursor.getLimit()), eq(null), anyInt(), nullable(String.class))).thenReturn(fakePaginationOptionsWithoutCursor);
        when(mockMovieRepository.findFirstWithFilter(any(MediaPaginationOptions.class))).thenReturn(fakeMovies);
        when(mockCursorUtil.encodeMediaCursor(any(MediaPaginationOptions.class), nullable(UUID.class), any())).thenReturn(new DefaultConnectionCursor("new-cursor"));

        var captor = ArgumentCaptor.forClass(Object.class);

        movieService.getMoviesWithFilter(1, null, 0, null, fakeFilter);

        verify(mockCursorUtil, times(1)).encodeMediaCursor(any(MediaPaginationOptions.class), nullable(UUID.class), captor.capture());

        Instant currentSortValue = (Instant) captor.getValue();

        assertThat(currentSortValue).isEqualTo(fakeMovies.get(0).getCreatedOn());
    }
}
