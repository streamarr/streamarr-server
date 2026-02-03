package com.streamarr.server.domain.streaming;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Builder
public class StreamSession {

  private static final String DEFAULT_VARIANT = "default";

  private final UUID sessionId;
  private final UUID mediaFileId;
  private final Path sourcePath;
  private final MediaProbe mediaProbe;
  private final TranscodeDecision transcodeDecision;
  private final StreamingOptions options;
  private final Instant createdAt;
  private final AtomicInteger activeRequestCount;

  @Builder.Default
  private final Map<String, TranscodeHandle> variantHandles = new ConcurrentHashMap<>();

  @Builder.Default private final List<QualityVariant> variants = Collections.emptyList();

  @Setter private volatile int seekPosition;
  @Setter private volatile Instant lastAccessedAt;

  public TranscodeHandle getHandle() {
    return variantHandles.get(DEFAULT_VARIANT);
  }

  public void setHandle(TranscodeHandle handle) {
    variantHandles.put(DEFAULT_VARIANT, handle);
  }

  public void setVariantHandle(String variantLabel, TranscodeHandle handle) {
    variantHandles.put(variantLabel, handle);
  }

  public TranscodeHandle getVariantHandle(String variantLabel) {
    return variantHandles.get(variantLabel);
  }

  public static String defaultVariant() {
    return DEFAULT_VARIANT;
  }
}
