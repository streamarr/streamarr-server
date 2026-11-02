package com.streamarr.server.graphql.mutation.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.AudioChannelsNotPositive;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.AudioEntriesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecLevelNotPositive;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecProfileOutsideCodecFamily;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecProfilesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecTierMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CodecTierNotApplicable;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.CompatibleBasesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.DolbyVisionLevelNotPositive;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.DolbyVisionProfileOutsideCodecFamily;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.HlsAacEntryMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyAudioEntries;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyCodecProfiles;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyCompatibleBases;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyDolbyVisionProfiles;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.TooManyVideoEntries;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoEntriesMissing;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoEntryMaximum;
import com.streamarr.server.services.streaming.CapabilityDeclarationRejection.VideoMaximumInvalid;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Capability Declaration Errors Tests")
class CapabilityDeclarationErrorsTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyRejection")
  @DisplayName("Should name the refused input field when a declaration rejection is mapped")
  void shouldNameTheRefusedInputFieldWhenADeclarationRejectionIsMapped(
      CapabilityDeclarationRejection rejection,
      Class<? extends InputMutationError> errorType,
      List<String> inputPath) {
    var error = CapabilityDeclarationErrors.toError(rejection);

    assertThat(error).isInstanceOf(errorType);
    assertThat(((InputMutationError) error).inputPath()).isEqualTo(inputPath);
    assertThat(error.message()).isNotBlank();
  }

  static Stream<Arguments> everyRejection() {
    return Stream.of(
        Arguments.of(
            new VideoEntriesMissing(),
            CreateStreamSessionError.CapabilityValuesRequiredError.class,
            List.of("capabilities", "video")),
        Arguments.of(
            new AudioEntriesMissing(),
            CreateStreamSessionError.CapabilityValuesRequiredError.class,
            List.of("capabilities", "audio")),
        Arguments.of(
            new HlsAacEntryMissing(),
            CreateStreamSessionError.HlsAacCapabilityRequiredError.class,
            List.of("capabilities", "audio")),
        Arguments.of(
            new TooManyVideoEntries(32),
            CreateStreamSessionError.CapabilityListTooLongError.class,
            List.of("capabilities", "video")),
        Arguments.of(
            new TooManyAudioEntries(32),
            CreateStreamSessionError.CapabilityListTooLongError.class,
            List.of("capabilities", "audio")),
        Arguments.of(
            new CodecProfilesMissing(1),
            CreateStreamSessionError.CapabilityValuesRequiredError.class,
            List.of("capabilities", "video", "1", "codecProfiles")),
        Arguments.of(
            new TooManyCodecProfiles(1, 32),
            CreateStreamSessionError.CapabilityListTooLongError.class,
            List.of("capabilities", "video", "1", "codecProfiles")),
        Arguments.of(
            new CompatibleBasesMissing(2),
            CreateStreamSessionError.CapabilityValuesRequiredError.class,
            List.of("capabilities", "video", "2", "compatibleBases")),
        Arguments.of(
            new TooManyCompatibleBases(2, 32),
            CreateStreamSessionError.CapabilityListTooLongError.class,
            List.of("capabilities", "video", "2", "compatibleBases")),
        Arguments.of(
            new TooManyDolbyVisionProfiles(0, 32),
            CreateStreamSessionError.CapabilityListTooLongError.class,
            List.of("capabilities", "video", "0", "dolbyVisionProfiles")),
        Arguments.of(
            new VideoMaximumInvalid(0, VideoEntryMaximum.BIT_DEPTH),
            CreateStreamSessionError.CapabilityMaximumInvalidError.class,
            List.of("capabilities", "video", "0", "maxBitDepth")),
        Arguments.of(
            new VideoMaximumInvalid(0, VideoEntryMaximum.WIDTH),
            CreateStreamSessionError.CapabilityMaximumInvalidError.class,
            List.of("capabilities", "video", "0", "maxWidth")),
        Arguments.of(
            new VideoMaximumInvalid(0, VideoEntryMaximum.HEIGHT),
            CreateStreamSessionError.CapabilityMaximumInvalidError.class,
            List.of("capabilities", "video", "0", "maxHeight")),
        Arguments.of(
            new VideoMaximumInvalid(0, VideoEntryMaximum.FRAME_RATE),
            CreateStreamSessionError.CapabilityMaximumInvalidError.class,
            List.of("capabilities", "video", "0", "maxFrameRate")),
        Arguments.of(
            new CodecProfileOutsideCodecFamily(0, 3),
            CreateStreamSessionError.CapabilityProfileMismatchError.class,
            List.of("capabilities", "video", "0", "codecProfiles", "3", "profile")),
        Arguments.of(
            new CodecLevelNotPositive(0, 3),
            CreateStreamSessionError.CapabilityMaximumInvalidError.class,
            List.of("capabilities", "video", "0", "codecProfiles", "3", "maxLevel")),
        Arguments.of(
            new CodecTierMissing(0, 3),
            CreateStreamSessionError.VideoCodecTierInvalidError.class,
            List.of("capabilities", "video", "0", "codecProfiles", "3", "maxTier")),
        Arguments.of(
            new CodecTierNotApplicable(0, 3),
            CreateStreamSessionError.VideoCodecTierInvalidError.class,
            List.of("capabilities", "video", "0", "codecProfiles", "3", "maxTier")),
        Arguments.of(
            new DolbyVisionProfileOutsideCodecFamily(4, 1),
            CreateStreamSessionError.CapabilityProfileMismatchError.class,
            List.of("capabilities", "video", "4", "dolbyVisionProfiles", "1", "profile")),
        Arguments.of(
            new DolbyVisionLevelNotPositive(4, 1),
            CreateStreamSessionError.CapabilityMaximumInvalidError.class,
            List.of("capabilities", "video", "4", "dolbyVisionProfiles", "1", "maxLevel")),
        Arguments.of(
            new AudioChannelsNotPositive(5),
            CreateStreamSessionError.CapabilityMaximumInvalidError.class,
            List.of("capabilities", "audio", "5", "maxChannels")));
  }

  @Test
  @DisplayName("Should carry the list's maximum when a list holds too many values")
  void shouldCarryTheListsMaximumWhenAListHoldsTooManyValues() {
    var error = CapabilityDeclarationErrors.toError(new TooManyCodecProfiles(0, 32));

    assertThat(error)
        .isInstanceOfSatisfying(
            CreateStreamSessionError.CapabilityListTooLongError.class,
            tooLong -> {
              assertThat(tooLong.maximum()).isEqualTo(32);
              assertThat(tooLong.message()).isEqualTo("List no more than 32 values here.");
            });
  }
}
