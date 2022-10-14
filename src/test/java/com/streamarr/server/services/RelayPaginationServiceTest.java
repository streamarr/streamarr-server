package com.streamarr.server.services;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.PaginationDirection;
import com.streamarr.server.graphql.cursor.PaginationOptions;
import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.Edge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@Tag("UnitTest")
@DisplayName("Relay Spec Pagination Service Tests")
public class RelayPaginationServiceTest {

    private final RelayPaginationService relayPaginationService = new RelayPaginationService();

    @Test
    @DisplayName("Should throw exception when paginating with both after and before arguments simultaneously.")
    void shouldThrowExceptionWhenPaginatingWithBothAfterAndBeforeCursors() {
        assertThatExceptionOfType(RuntimeException.class)
            .isThrownBy(() -> relayPaginationService.getPaginationOptions(10, "cursor", 0, "cursor"))
            .withMessageContaining("Cannot request with both after and before simultaneously.");
    }

    @Test
    @DisplayName("Should throw exception when paginating with negative first argument.")
    void shouldThrowExceptionWhenPaginatingWithNegativeFirstLimit() {
        assertThatExceptionOfType(RuntimeException.class)
            .isThrownBy(() -> relayPaginationService.getPaginationOptions(-1, "cursor", 0, null))
            .withMessageContaining("first must be greater than zero.");
    }

    @Test
    @DisplayName("Should throw exception when paginating with negative last argument.")
    void shouldThrowExceptionWhenPaginatingWithNegativeLastLimit() {
        assertThatExceptionOfType(RuntimeException.class)
            .isThrownBy(() -> relayPaginationService.getPaginationOptions(0, null, -1, "cursor"))
            .withMessageContaining("last must be greater than zero.");
    }

    @Test
    @DisplayName("Should throw exception when paginating with first argument that is too large.")
    void shouldThrowExceptionWhenPaginatingWithFirstLimitTooLarge() {
        assertThatExceptionOfType(RuntimeException.class)
            .isThrownBy(() -> relayPaginationService.getPaginationOptions(501, "cursor", 0, null))
            .withMessageContaining("first must be less than 500.");
    }

    @Test
    @DisplayName("Should throw exception when paginating with last argument that is too large.")
    void shouldThrowExceptionWhenPaginatingWithLastLimitTooLarge() {
        assertThatExceptionOfType(RuntimeException.class)
            .isThrownBy(() -> relayPaginationService.getPaginationOptions(0, null, 501, "cursor"))
            .withMessageContaining("last must be less than 500.");
    }

    @Test
    @DisplayName("Should throw exception when paginating with no cursor and first argument is too large.")
    void shouldThrowExceptionWhenPaginatingWithNoCursorAndFirstLimitTooLarge() {
        assertThatExceptionOfType(RuntimeException.class)
            .isThrownBy(() -> relayPaginationService.getPaginationOptions(501, null, 0, null))
            .withMessageContaining("first must be less than 500.");
    }

    @Test
    @DisplayName("Should throw exception when paginating with no cursor and only last argument.")
    void shouldThrowExceptionWhenPaginatingWithNoCursorAndOnlyLast() {
        assertThatExceptionOfType(RuntimeException.class)
            .isThrownBy(() -> relayPaginationService.getPaginationOptions(0, null, 1, null))
            .withMessageContaining("first must be greater than zero.");
    }

    @Test
    @DisplayName("Should paginate forward when given first and after.")
    void shouldPaginateForwardWhenGivenFirstAndAfter() {
        var options = relayPaginationService.getPaginationOptions(1, "cursor", 0, null);

        assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.FORWARD);
    }

    @Test
    @DisplayName("Should paginate forward when given first.")
    void shouldPaginateForwardWhenGivenFirst() {
        var options = relayPaginationService.getPaginationOptions(1, null, 0, null);

        assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.FORWARD);
    }

    @Test
    @DisplayName("Should paginate backward when given last and before.")
    void shouldPaginateBackwardWhenGivenLastAndBefore() {
        var options = relayPaginationService.getPaginationOptions(0, null, 1, "cursor");

        assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.REVERSE);
    }

    @Test
    @DisplayName("Should get cursor when given both first and after")
    void shouldHaveCursorWhenGivenBothFirstAndAfter() {
        var cursor = "cursor";

        var options = relayPaginationService.getPaginationOptions(1, cursor, 0, null);

        assertThat(options.getCursor()).isPresent();
        assertThat(options.getCursor().get()).isEqualTo(cursor);

    }

    @Test
    @DisplayName("Should get cursor when given both last and before")
    void shouldHaveCursorWhenGivenBothLastAndBefore() {
        var cursor = "cursor";

        var options = relayPaginationService.getPaginationOptions(0, null, 1, cursor);

        assertThat(options.getCursor()).isPresent();
        assertThat(options.getCursor().get()).isEqualTo(cursor);
    }

    @Test
    @DisplayName("Should get limit when given first")
    void shouldGetLimitWhenGivenFirst() {
        var options = relayPaginationService.getPaginationOptions(1, null, 0, null);

        assertThat(options.getLimit()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should get limit when given first and after")
    void shouldGetLimitWhenGivenFirstAndAfter() {
        var options = relayPaginationService.getPaginationOptions(1, "cursor", 0, null);

        assertThat(options.getLimit()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should get limit when given last and before")
    void shouldGetLimitWhenGivenLastAndBefore() {
        var options = relayPaginationService.getPaginationOptions(0, null, 1, "cursor");

        assertThat(options.getLimit()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should build connection when given first result in non-seek result list.")
    void shouldBuildConnectionWhenGivenSingleResultInNonSeekList() {

        List<Edge<? extends BaseAuditableEntity<?>>> edges = List.of(
            new DefaultEdge<>(Movie.builder().build(), new DefaultConnectionCursor("cursor"))
        );

        var options = PaginationOptions.builder()
            .cursor(Optional.empty())
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

        Optional<UUID> cursorId = Optional.empty();

        var connection = relayPaginationService.buildConnection(edges, options, cursorId);

        assertThat(connection.getEdges().size()).isEqualTo(1);
        assertThat(connection.getPageInfo().getEndCursor()).isEqualTo(edges.get(0).getCursor());
        assertThat(connection.getPageInfo().getStartCursor()).isEqualTo(edges.get(0).getCursor());
        assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
        assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should build connection of one when limited by one given multiple results in non-seek result list.")
    void shouldBuildConnectionOfOneWhenLimitedByOneGivenMultipleResultsInNonSeekList() {

        List<Edge<? extends BaseAuditableEntity<?>>> edges = List.of(
            new DefaultEdge<>(Movie.builder().build(), new DefaultConnectionCursor("cursor")),
            new DefaultEdge<>(Movie.builder().build(), new DefaultConnectionCursor("cursor"))
        );

        var options = PaginationOptions.builder()
            .cursor(Optional.empty())
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

        Optional<UUID> cursorId = Optional.empty();

        var connection = relayPaginationService.buildConnection(edges, options, cursorId);

        assertThat(connection.getEdges().size()).isEqualTo(1);
        assertThat(connection.getPageInfo().getEndCursor()).isEqualTo(edges.get(0).getCursor());
        assertThat(connection.getPageInfo().getStartCursor()).isEqualTo(edges.get(0).getCursor());
        assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
        assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should build connection with empty edges when paginating forward given empty list")
    void shouldBuildEmptyConnectionWhenPaginatingForwardGivenEmptyList() {

        List<Edge<? extends BaseAuditableEntity<?>>> edges = Collections.emptyList();

        var options = PaginationOptions.builder()
            .cursor(Optional.empty())
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

        Optional<UUID> cursorId = Optional.empty();

        var connection = relayPaginationService.buildConnection(edges, options, cursorId);

        assertThat(connection.getEdges().isEmpty()).isTrue();
        assertThat(connection.getPageInfo().getEndCursor()).isNull();
        assertThat(connection.getPageInfo().getStartCursor()).isNull();
        assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
        assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should build connection with empty edges when paginating backward given empty list")
    void shouldBuildEmptyConnectionWhenPaginatingReverseGivenEmptyList() {

        List<Edge<? extends BaseAuditableEntity<?>>> edges = Collections.emptyList();

        var options = PaginationOptions.builder()
            .cursor(Optional.empty())
            .paginationDirection(PaginationDirection.REVERSE)
            .limit(1)
            .build();

        Optional<UUID> cursorId = Optional.empty();

        var connection = relayPaginationService.buildConnection(edges, options, cursorId);

        assertThat(connection.getEdges().isEmpty()).isTrue();
        assertThat(connection.getPageInfo().getEndCursor()).isNull();
        assertThat(connection.getPageInfo().getStartCursor()).isNull();
        assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
        assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should build connection with empty edges when paginating forward given list containing only cursorId")
    void shouldBuildEmptyConnectionWhenPaginatingForwardGivenListContainingCursorId() {

        var itemId = UUID.randomUUID();
        var cursorString = "cursor-placeholder";

        List<Edge<? extends BaseAuditableEntity<?>>> edges = List.of(
            new DefaultEdge<>(Movie.builder().id(itemId).build(), new DefaultConnectionCursor(cursorString))
        );

        var options = PaginationOptions.builder()
            .cursor(Optional.of(cursorString))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

        var connection = relayPaginationService.buildConnection(edges, options, Optional.of(itemId));

        assertThat(connection.getEdges().isEmpty()).isTrue();
        assertThat(connection.getPageInfo().getEndCursor()).isNull();
        assertThat(connection.getPageInfo().getStartCursor()).isNull();
        assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
        assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should build connection with empty edges when paginating backward given list containing only cursorId")
    void shouldBuildEmptyConnectionWhenPaginatingBackwardGivenListContainingCursorId() {

        var itemId = UUID.randomUUID();
        var cursorString = "cursor-placeholder";

        List<Edge<? extends BaseAuditableEntity<?>>> edges = List.of(
            new DefaultEdge<>(Movie.builder().id(itemId).build(), new DefaultConnectionCursor(cursorString))
        );

        var options = PaginationOptions.builder()
            .cursor(Optional.of(cursorString))
            .paginationDirection(PaginationDirection.REVERSE)
            .limit(1)
            .build();

        var connection = relayPaginationService.buildConnection(edges, options, Optional.of(itemId));

        assertThat(connection.getEdges().isEmpty()).isTrue();
        assertThat(connection.getPageInfo().getEndCursor()).isNull();
        assertThat(connection.getPageInfo().getStartCursor()).isNull();
        assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
        assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should build connection when paginating forward given list containing result from previous page")
    void shouldBuildConnectionWhenPaginatingForwardGivenSeekListContainingPreviousPage() {

        var itemId1 = UUID.randomUUID();
        var itemId2 = UUID.randomUUID();

        var cursorString = "cursor-placeholder";

        List<Edge<? extends BaseAuditableEntity<?>>> edges = List.of(
            new DefaultEdge<>(Movie.builder().id(itemId1).build(), new DefaultConnectionCursor(cursorString)),
            new DefaultEdge<>(Movie.builder().id(itemId2).build(), new DefaultConnectionCursor(cursorString))
        );

        var options = PaginationOptions.builder()
            .cursor(Optional.of(cursorString))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

        var connection = relayPaginationService.buildConnection(edges, options, Optional.of(itemId1));

        assertThat(connection.getEdges().size()).isEqualTo(1);
        assertThat(connection.getPageInfo().getEndCursor()).isEqualTo(edges.get(1).getCursor());
        assertThat(connection.getPageInfo().getStartCursor()).isEqualTo(edges.get(1).getCursor());
        assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
        assertThat(connection.getPageInfo().isHasPreviousPage()).isTrue();
    }

    @Test
    @DisplayName("Should build connection when paginating backward given list containing result from next page")
    void shouldBuildConnectionWhenPaginatingBackwardGivenSeekListContainingNextPage() {

        var itemId1 = UUID.randomUUID();
        var itemId2 = UUID.randomUUID();

        var cursorString = "cursor-placeholder";

        List<Edge<? extends BaseAuditableEntity<?>>> edges = List.of(
            new DefaultEdge<>(Movie.builder().id(itemId2).build(), new DefaultConnectionCursor(cursorString)),
            new DefaultEdge<>(Movie.builder().id(itemId1).build(), new DefaultConnectionCursor(cursorString))
        );

        var options = PaginationOptions.builder()
            .cursor(Optional.of(cursorString))
            .paginationDirection(PaginationDirection.REVERSE)
            .limit(1)
            .build();

        var connection = relayPaginationService.buildConnection(edges, options, Optional.of(itemId1));

        assertThat(connection.getEdges().size()).isEqualTo(1);
        assertThat(connection.getPageInfo().getEndCursor()).isEqualTo(edges.get(1).getCursor());
        assertThat(connection.getPageInfo().getStartCursor()).isEqualTo(edges.get(1).getCursor());
        assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
        assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should build connection when paginating forward given list containing result from previous page and result from next page")
    void shouldBuildConnectionWhenPaginatingForwardGivenSeekListContainingPreviousPageAndNextPage() {

        var itemId1 = UUID.randomUUID();
        var itemId2 = UUID.randomUUID();
        var itemId3 = UUID.randomUUID();

        var cursorString = "cursor-placeholder";

        List<Edge<? extends BaseAuditableEntity<?>>> edges = List.of(
            new DefaultEdge<>(Movie.builder().id(itemId1).build(), new DefaultConnectionCursor(cursorString)),
            new DefaultEdge<>(Movie.builder().id(itemId2).build(), new DefaultConnectionCursor(cursorString)),
            new DefaultEdge<>(Movie.builder().id(itemId3).build(), new DefaultConnectionCursor(cursorString))

        );

        var options = PaginationOptions.builder()
            .cursor(Optional.of(cursorString))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

        var connection = relayPaginationService.buildConnection(edges, options, Optional.of(itemId1));

        assertThat(connection.getEdges().size()).isEqualTo(1);
        assertThat(connection.getPageInfo().getEndCursor()).isEqualTo(edges.get(1).getCursor());
        assertThat(connection.getPageInfo().getStartCursor()).isEqualTo(edges.get(1).getCursor());
        assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
        assertThat(connection.getPageInfo().isHasPreviousPage()).isTrue();
    }

    @Test
    @DisplayName("Should build connection when paginating backward given list containing result from next page and previous page")
    void shouldBuildConnectionWhenPaginatingBackwardGivenSeekListContainingNextPageAndPreviousPage() {

        var itemId1 = UUID.randomUUID();
        var itemId2 = UUID.randomUUID();
        var itemId3 = UUID.randomUUID();

        var cursorString = "cursor-placeholder";

        List<Edge<? extends BaseAuditableEntity<?>>> edges = List.of(
            new DefaultEdge<>(Movie.builder().id(itemId3).build(), new DefaultConnectionCursor(cursorString)),
            new DefaultEdge<>(Movie.builder().id(itemId2).build(), new DefaultConnectionCursor(cursorString)),
            new DefaultEdge<>(Movie.builder().id(itemId1).build(), new DefaultConnectionCursor(cursorString))
        );

        var options = PaginationOptions.builder()
            .cursor(Optional.of(cursorString))
            .paginationDirection(PaginationDirection.REVERSE)
            .limit(1)
            .build();

        var connection = relayPaginationService.buildConnection(edges, options, Optional.of(itemId1));

        assertThat(connection.getEdges().size()).isEqualTo(1);
        assertThat(connection.getPageInfo().getEndCursor()).isEqualTo(edges.get(1).getCursor());
        assertThat(connection.getPageInfo().getStartCursor()).isEqualTo(edges.get(1).getCursor());
        assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
        assertThat(connection.getPageInfo().isHasPreviousPage()).isTrue();
    }

}
