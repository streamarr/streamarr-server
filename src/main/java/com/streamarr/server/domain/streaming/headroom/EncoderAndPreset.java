package com.streamarr.server.domain.streaming.headroom;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.EncoderPreset;
import lombok.NonNull;

/** The encoder backend and preset that select a job attempt's encode constant. */
public record EncoderAndPreset(@NonNull EncoderBackend backend, @NonNull EncoderPreset preset)
    implements CostConstant {}
