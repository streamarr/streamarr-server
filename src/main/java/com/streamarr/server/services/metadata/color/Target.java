/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Streamarr contributors: adapted from AndroidX Target (androidx commit
 * 9748764301e5dce66cbf297f6778fa658768c213); reduced to the six default targets as an enum
 * declared in scoring order with VIBRANT first, keeping the upstream weights fixed and every
 * target exclusive. The Builder, custom targets, and weight normalization are removed. See
 * THIRD_PARTY_NOTICES.md.
 */
package com.streamarr.server.services.metadata.color;

/**
 * Saturation and lightness profiles a swatch can fill, in the order {@link Palette} scores them.
 */
enum Target {
  VIBRANT(Ranges.VIBRANT_SATURATION, Ranges.NORMAL_LIGHTNESS),
  LIGHT_VIBRANT(Ranges.VIBRANT_SATURATION, Ranges.LIGHT_LIGHTNESS),
  DARK_VIBRANT(Ranges.VIBRANT_SATURATION, Ranges.DARK_LIGHTNESS),
  LIGHT_MUTED(Ranges.MUTED_SATURATION, Ranges.LIGHT_LIGHTNESS),
  MUTED(Ranges.MUTED_SATURATION, Ranges.NORMAL_LIGHTNESS),
  DARK_MUTED(Ranges.MUTED_SATURATION, Ranges.DARK_LIGHTNESS);

  private static final float WEIGHT_SATURATION = 0.24f;
  private static final float WEIGHT_LIGHTNESS = 0.52f;
  private static final float WEIGHT_POPULATION = 0.24f;

  private final Range saturation;
  private final Range lightness;

  Target(Range saturation, Range lightness) {
    this.saturation = saturation;
    this.lightness = lightness;
  }

  boolean accepts(float[] hsl) {
    return saturation.contains(hsl[1]) && lightness.contains(hsl[2]);
  }

  /** Scores a swatch whose population is {@code populationShare} of the dominant swatch's. */
  float score(float[] hsl, float populationShare) {
    return WEIGHT_SATURATION * (1f - Math.abs(hsl[1] - saturation.target()))
        + WEIGHT_LIGHTNESS * (1f - Math.abs(hsl[2] - lightness.target()))
        + WEIGHT_POPULATION * populationShare;
  }

  private record Range(float min, float target, float max) {

    boolean contains(float value) {
      return value >= min && value <= max;
    }
  }

  private static final class Ranges {

    static final Range DARK_LIGHTNESS = new Range(0f, 0.26f, 0.45f);
    static final Range NORMAL_LIGHTNESS = new Range(0.3f, 0.5f, 0.7f);
    static final Range LIGHT_LIGHTNESS = new Range(0.55f, 0.74f, 1f);
    static final Range MUTED_SATURATION = new Range(0f, 0.3f, 0.4f);
    static final Range VIBRANT_SATURATION = new Range(0.35f, 1f, 1f);

    private Ranges() {}
  }
}
