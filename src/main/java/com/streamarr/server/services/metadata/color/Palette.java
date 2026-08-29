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
 * Modified by Streamarr contributors: the target scoring and exclusive swatch selection are
 * adapted from AndroidX Palette (androidx commit 9748764301e5dce66cbf297f6778fa658768c213),
 * operating on an already-quantized swatch list. The Builder, Bitmap handling, filters, region
 * support, and per-target getters are removed, and VIBRANT is scored before LIGHT_VIBRANT so the
 * primary color matches the previous single-target search. See THIRD_PARTY_NOTICES.md.
 */
package com.streamarr.server.services.metadata.color;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Assigns each {@link Target} the best-scoring unused swatch from a quantized artwork palette. */
final class Palette {

  private final Swatch dominant;
  private final Map<Target, Swatch> selected = new EnumMap<>(Target.class);
  private final Set<Integer> usedColors = new HashSet<>();

  Palette(List<Swatch> swatches) {
    if (swatches.isEmpty()) {
      throw new IllegalArgumentException("A palette needs at least one swatch");
    }
    dominant = swatches.stream().max(Comparator.comparingInt(Swatch::population)).orElseThrow();
    for (var target : Target.values()) {
      select(target, swatches);
    }
  }

  Optional<Swatch> swatchFor(Target target) {
    return Optional.ofNullable(selected.get(target));
  }

  Swatch dominantSwatch() {
    return dominant;
  }

  private void select(Target target, List<Swatch> swatches) {
    Swatch best = null;
    var bestScore = 0f;
    for (var swatch : swatches) {
      if (usedColors.contains(swatch.rgb()) || !target.accepts(swatch.hsl())) {
        continue;
      }
      var score = target.score(swatch.hsl(), swatch.population() / (float) dominant.population());
      if (best != null && score <= bestScore) {
        continue;
      }
      best = swatch;
      bestScore = score;
    }

    if (best == null) {
      return;
    }
    selected.put(target, best);
    usedColors.add(best.rgb());
  }
}
