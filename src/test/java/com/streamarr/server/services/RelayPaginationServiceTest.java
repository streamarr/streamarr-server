package com.streamarr.server.services;

import com.streamarr.server.graphql.cursor.PaginationDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

}
