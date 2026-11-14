package com.streamarr.server.repositories.streaming;

import com.streamarr.server.domain.streaming.headroom.CalibrationKey;
import com.streamarr.server.domain.streaming.headroom.KeptCalibration;
import java.util.Optional;

/** Keeps the last calibration for each worker hardware, CPU limit and FFmpeg build (ADR 0044). */
public interface WorkerCalibrationRepository {

  /**
   * Keeps the calibration for its key, replacing the one kept earlier for the same key.
   *
   * @return whether the calibration was kept; false when a calibration kept later is already stored
   */
  boolean keep(KeptCalibration calibration);

  Optional<KeptCalibration> findByKey(CalibrationKey key);
}
