package com.streamarr.server.domain.streaming;

/** Declared MaxCLL and MaxFALL in cd/m²; zero remains a declared value. */
public record ContentLightLevel(int maxContentLightLevel, int maxFrameAverageLightLevel) {}
