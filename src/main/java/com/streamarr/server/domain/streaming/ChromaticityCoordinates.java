package com.streamarr.server.domain.streaming;

/** CIE 1931 coordinates in units of 1/50000, as stored in the mastering display SEI. */
public record ChromaticityCoordinates(int x, int y) {}
