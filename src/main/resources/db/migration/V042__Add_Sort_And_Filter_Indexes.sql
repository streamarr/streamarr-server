-- Keyset pagination indexes for RELEASE_DATE and RUNTIME sorts
CREATE INDEX idx_movie_releasedate_id ON movie (release_date, id);
CREATE INDEX idx_movie_runtime_id ON movie (runtime, id);
CREATE INDEX idx_series_firstairdate_id ON series (first_air_date, id);
CREATE INDEX idx_series_runtime_id ON series (runtime, id);
