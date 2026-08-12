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
 * Modified by Streamarr contributors: adapted from AndroidX Palette.Filter and its
 * DEFAULT_FILTER (androidx commit 9748764301e5dce66cbf297f6778fa658768c213). The skin-tone
 * exclusion is upstream's "near red I line" heuristic. See THIRD_PARTY_NOTICES.md.
 */
package com.streamarr.server.services.metadata.color;

@FunctionalInterface
interface SwatchFilter {

  float BLACK_MAX_LIGHTNESS = 0.05f;
  float WHITE_MIN_LIGHTNESS = 0.95f;
  float SKIN_HUE_MIN = 10f;
  float SKIN_HUE_MAX = 37f;
  float SKIN_MAX_SATURATION = 0.82f;

  SwatchFilter ALLOW_ALL = (rgb, hsl) -> true;
  SwatchFilter DEFAULT =
      (rgb, hsl) -> !isNearWhite(hsl) && !isNearBlack(hsl) && !isNearSkinTone(hsl);

  boolean isAllowed(int rgb, float[] hsl);

  private static boolean isNearBlack(float[] hsl) {
    return hsl[2] <= BLACK_MAX_LIGHTNESS;
  }

  private static boolean isNearWhite(float[] hsl) {
    return hsl[2] >= WHITE_MIN_LIGHTNESS;
  }

  private static boolean isNearSkinTone(float[] hsl) {
    return hsl[0] >= SKIN_HUE_MIN && hsl[0] <= SKIN_HUE_MAX && hsl[1] <= SKIN_MAX_SATURATION;
  }
}
