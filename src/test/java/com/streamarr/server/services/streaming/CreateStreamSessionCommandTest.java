package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Create Stream Session Command Tests")
class CreateStreamSessionCommandTest {

  @Test
  @DisplayName("Should refuse a command when it carries no capability declaration")
  void shouldRefuseACommandWhenItCarriesNoCapabilityDeclaration() {
    var command =
        CreateStreamSessionCommand.builder()
            .streamSessionId(UUID.randomUUID())
            .mediaFileId(UUID.randomUUID())
            .identity(identityFor(playbackAuthorityFor(UUID.randomUUID())))
            .options(StreamingOptions.builder().quality(VideoQuality.AUTO).build());

    assertThatThrownBy(command::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("capabilityDeclaration");
  }
}
