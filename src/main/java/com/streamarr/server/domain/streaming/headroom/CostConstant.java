package com.streamarr.server.domain.streaming.headroom;

/** What selects one constant of the headroom model. */
public sealed interface CostConstant permits EncoderAndPreset, SourceDecoder, ToneMapping {}
