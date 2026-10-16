package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("New Library Operation Tests")
class NewLibraryOperationTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();

  @Test
  @DisplayName("Should carry no image refresh mode when a scan is requested")
  void shouldCarryNoImageRefreshModeWhenAScanIsRequested() {
    var scan = NewLibraryOperation.scan(LIBRARY_ID);

    assertThat(scan)
        .isEqualTo(
            new NewLibraryOperation(LIBRARY_ID, LibraryOperationKind.SCAN, Optional.empty()));
  }

  @Test
  @DisplayName("Should carry the image refresh mode when a refresh is requested")
  void shouldCarryTheImageRefreshModeWhenARefreshIsRequested() {
    var refresh = NewLibraryOperation.refresh(LIBRARY_ID, ImageRefreshMode.FORCE_REFRESH);

    assertThat(refresh)
        .isEqualTo(
            new NewLibraryOperation(
                LIBRARY_ID,
                LibraryOperationKind.REFRESH,
                Optional.of(ImageRefreshMode.FORCE_REFRESH)));
  }

  @Test
  @DisplayName("Should reject a scan when it carries an image refresh mode")
  void shouldRejectAScanWhenItCarriesAnImageRefreshMode() {
    var imageRefreshMode = Optional.of(ImageRefreshMode.PRESERVE);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new NewLibraryOperation(LIBRARY_ID, LibraryOperationKind.SCAN, imageRefreshMode));
  }

  @Test
  @DisplayName("Should reject a refresh when it carries no image refresh mode")
  void shouldRejectARefreshWhenItCarriesNoImageRefreshMode() {
    var noImageRefreshMode = Optional.<ImageRefreshMode>empty();

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new NewLibraryOperation(
                    LIBRARY_ID, LibraryOperationKind.REFRESH, noImageRefreshMode));
  }

  @Test
  @DisplayName("Should reject an operation when a required component is missing")
  void shouldRejectAnOperationWhenARequiredComponentIsMissing() {
    var noImageRefreshMode = Optional.<ImageRefreshMode>empty();

    assertThatNullPointerException()
        .isThrownBy(
            () -> new NewLibraryOperation(null, LibraryOperationKind.SCAN, noImageRefreshMode))
        .withMessageContaining("libraryId");
    assertThatNullPointerException()
        .isThrownBy(() -> new NewLibraryOperation(LIBRARY_ID, null, noImageRefreshMode))
        .withMessageContaining("kind");
    assertThatNullPointerException()
        .isThrownBy(() -> new NewLibraryOperation(LIBRARY_ID, LibraryOperationKind.SCAN, null))
        .withMessageContaining("imageRefreshMode");
  }

  @Test
  @DisplayName(
      "Should reject a scan or a refresh when its library or image refresh mode is missing")
  void shouldRejectAScanOrARefreshWhenItsLibraryOrImageRefreshModeIsMissing() {
    assertThatNullPointerException()
        .isThrownBy(() -> NewLibraryOperation.scan(null))
        .withMessageContaining("libraryId");
    assertThatNullPointerException()
        .isThrownBy(() -> NewLibraryOperation.refresh(null, ImageRefreshMode.PRESERVE))
        .withMessageContaining("libraryId");
    assertThatNullPointerException()
        .isThrownBy(() -> NewLibraryOperation.refresh(LIBRARY_ID, null))
        .withMessageContaining("imageRefreshMode");
  }
}
