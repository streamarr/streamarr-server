package com.streamarr.transcode.protocol;

import com.streamarr.transcode.v1.Uuid;
import java.util.UUID;

public final class ProtoUuid {

  private ProtoUuid() {}

  public static Uuid toProto(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }

  public static UUID fromProto(Uuid value) {
    return new UUID(value.getMostSignificantBits(), value.getLeastSignificantBits());
  }
}
