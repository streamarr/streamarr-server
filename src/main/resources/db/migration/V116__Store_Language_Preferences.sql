-- Each Profile's language preferences and its overrides of them for one title (ADR 0043,
-- streamarr/streamarr-server#753). A language is stored as the name of a value of the server's
-- Language table, such as FR or PT_BR.
--
-- audio_languages lists at most five languages in the Profile's order of preference. The server
-- refuses a list that names a language twice. subtitle_mode is FORCED until the Profile changes it.
-- Adding a column with a constant default rewrites no row.
ALTER TABLE profile
    ADD COLUMN audio_languages   TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN subtitle_language TEXT,
    ADD COLUMN subtitle_mode     TEXT   NOT NULL DEFAULT 'FORCED',
    ADD CONSTRAINT profile_audio_languages_at_most_five CHECK (
        cardinality(audio_languages) <= 5 AND array_position(audio_languages, NULL) IS NULL),
    ADD CONSTRAINT profile_subtitle_mode_check CHECK (
        subtitle_mode IN ('OFF', 'FORCED', 'ALWAYS', 'FOREIGN_AUDIO'));

-- One row per Profile and title: a series, a season, an episode or a movie, named by exactly one
-- of the four title columns. A NULL field inherits from the next wider level, and the server
-- removes a row whose three fields are all NULL. Each title column cascades from its own table,
-- because deleting a series alone leaves the base_collectable rows of its seasons and episodes.
CREATE TABLE language_override
(
    id                UUID                     NOT NULL DEFAULT gen_random_uuid(),
    profile_id        UUID                     NOT NULL REFERENCES profile (id) ON DELETE CASCADE,
    movie_id          UUID REFERENCES movie (id) ON DELETE CASCADE,
    series_id         UUID REFERENCES series (id) ON DELETE CASCADE,
    season_id         UUID REFERENCES season (id) ON DELETE CASCADE,
    episode_id        UUID REFERENCES episode (id) ON DELETE CASCADE,
    audio_language    TEXT,
    subtitle_language TEXT,
    subtitle_mode     TEXT,
    created_on        TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by        UUID                     NOT NULL,
    last_modified_on  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_modified_by  UUID,
    CONSTRAINT language_override_pkey PRIMARY KEY (id),
    CONSTRAINT language_override_names_one_title CHECK (
        num_nonnulls(movie_id, series_id, season_id, episode_id) = 1),
    CONSTRAINT language_override_subtitle_mode_check CHECK (
        subtitle_mode IN ('OFF', 'FORCED', 'ALWAYS', 'FOREIGN_AUDIO'))
);

-- Each index is the conflict target of its title's upsert and serves the cascade from its title.
CREATE UNIQUE INDEX language_override_movie_profile_key
    ON language_override (movie_id, profile_id) WHERE movie_id IS NOT NULL;
CREATE UNIQUE INDEX language_override_series_profile_key
    ON language_override (series_id, profile_id) WHERE series_id IS NOT NULL;
CREATE UNIQUE INDEX language_override_season_profile_key
    ON language_override (season_id, profile_id) WHERE season_id IS NOT NULL;
CREATE UNIQUE INDEX language_override_episode_profile_key
    ON language_override (episode_id, profile_id) WHERE episode_id IS NOT NULL;
CREATE INDEX language_override_profile_id_idx ON language_override (profile_id);

CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON language_override
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();
