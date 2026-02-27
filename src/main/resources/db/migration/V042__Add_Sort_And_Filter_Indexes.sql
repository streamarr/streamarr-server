-- Keyset pagination indexes for RELEASE_DATE and RUNTIME sorts (ASC)
-- Default B-tree null ordering (nulls high) matches NULLS LAST for ASC
CREATE INDEX idx_movie_releasedate_id ON movie (release_date, id);
CREATE INDEX idx_movie_runtime_id ON movie (runtime, id);
CREATE INDEX idx_series_firstairdate_id ON series (first_air_date, id);
CREATE INDEX idx_series_runtime_id ON series (runtime, id);

-- DESC + NULLS LAST variants (default DESC puts nulls first, explicit override needed)
CREATE INDEX idx_movie_releasedate_desc_id ON movie (release_date DESC NULLS LAST, id DESC);
CREATE INDEX idx_movie_runtime_desc_id ON movie (runtime DESC NULLS LAST, id DESC);
CREATE INDEX idx_series_firstairdate_desc_id ON series (first_air_date DESC NULLS LAST, id DESC);
CREATE INDEX idx_series_runtime_desc_id ON series (runtime DESC NULLS LAST, id DESC);
