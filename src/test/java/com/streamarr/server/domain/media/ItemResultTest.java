package com.streamarr.server.domain.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Item Result Tests")
class ItemResultTest {

  @Test
  @DisplayName("Should reject an artwork result when it names no artwork generation")
  void shouldRejectAnArtworkResultWhenItNamesNoArtworkGeneration() {
    var result = artwork().artworkGeneration(null);

    assertThatIllegalArgumentException().isThrownBy(result::build);
  }

  @Test
  @DisplayName("Should reject an artwork result when it names no image type")
  void shouldRejectAnArtworkResultWhenItNamesNoImageType() {
    var result = artwork().imageType(null);

    assertThatIllegalArgumentException().isThrownBy(result::build);
  }

  @Test
  @DisplayName("Should reject a metadata result when it names an artwork generation")
  void shouldRejectAMetadataResultWhenItNamesAnArtworkGeneration() {
    var result = metadata().artworkGeneration(1L);

    assertThatIllegalArgumentException().isThrownBy(result::build);
  }

  @Test
  @DisplayName("Should reject a metadata result when it names an image type")
  void shouldRejectAMetadataResultWhenItNamesAnImageType() {
    var result = metadata().imageType(ImageType.POSTER);

    assertThatIllegalArgumentException().isThrownBy(result::build);
  }

  @Test
  @DisplayName("Should be final when the image was saved or the provider has none")
  void shouldBeFinalWhenTheImageWasSavedOrTheProviderHasNone() {
    assertThat(artwork().outcome(new ItemOutcome.Succeeded()).build().isFinal()).isTrue();
    assertThat(artwork().outcome(new ItemOutcome.Unavailable()).build().isFinal()).isTrue();
  }

  @Test
  @DisplayName("Should not be final when the failure is temporary")
  void shouldNotBeFinalWhenTheFailureIsTemporary() {
    var failure = new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "timeout");

    assertThat(artwork().outcome(failure).build().isFinal()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(value = ItemFailureReason.class, names = "TEMPORARY", mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should be final when a retry would meet the same failure")
  void shouldBeFinalWhenARetryWouldMeetTheSameFailure(ItemFailureReason reason) {
    var failure = new ItemOutcome.Failed(reason, "detail");

    assertThat(artwork().outcome(failure).build().isFinal()).isTrue();
  }

  private static ItemResult.ItemResultBuilder artwork() {
    return ItemResult.builder()
        .itemId(UUID.randomUUID())
        .itemType(ImageEntityType.MOVIE)
        .step(ItemStep.ARTWORK)
        .imageType(ImageType.POSTER)
        .outcome(new ItemOutcome.Succeeded())
        .attemptedAt(Instant.EPOCH)
        .artworkGeneration(1L);
  }

  private static ItemResult.ItemResultBuilder metadata() {
    return ItemResult.builder()
        .itemId(UUID.randomUUID())
        .itemType(ImageEntityType.MOVIE)
        .step(ItemStep.METADATA)
        .outcome(new ItemOutcome.Succeeded())
        .attemptedAt(Instant.EPOCH);
  }
}
