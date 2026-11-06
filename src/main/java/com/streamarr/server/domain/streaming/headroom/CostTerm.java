package com.streamarr.server.domain.streaming.headroom;

/** One term of a job attempt's cost: the constant that prices it and the pixel rate it prices. */
public record CostTerm(CostConstant constant, double megapixelsPerSecond) {}
