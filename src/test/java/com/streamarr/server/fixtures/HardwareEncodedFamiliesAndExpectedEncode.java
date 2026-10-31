package com.streamarr.server.fixtures;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.support.ParameterDeclarations;

/**
 * The codec families that workers encode in hardware, and the codec family that an encode uses for
 * a client whose entries declare AV1 and H.264 at the same frame rate.
 */
public final class HardwareEncodedFamiliesAndExpectedEncode implements ArgumentsProvider {

  @Override
  public Stream<Arguments> provideArguments(
      ParameterDeclarations parameters, ExtensionContext context) {
    return Stream.of(
        Arguments.of(Set.of(), "h264"),
        Arguments.of(Set.of("h264"), "h264"),
        Arguments.of(Set.of("av1"), "av1"),
        Arguments.of(Set.of("h264", "av1"), "av1"));
  }
}
