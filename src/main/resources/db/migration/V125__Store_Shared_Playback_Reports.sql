-- A playback report that a viewer shares with the Server Admin from the Playback Data panel (#751):
-- the player's figures and the viewer's note, with the server's decisions for the playback session
-- in playback_report_decision. Each figure is NULL when the player did not know it. The server
-- deletes a report 30 days after it was shared.
--
-- capability_declaration keeps the playback session's client capability declaration (ADR 0049)
-- as the document the server validated when the playback session started. It is shown whole and
-- never queried.
CREATE TABLE playback_report
(
    id                             UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on                     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by                     UUID,
    last_modified_on               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by               UUID,
    shared_at                      TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at                        TIMESTAMP WITH TIME ZONE,
    profile_id                     UUID                     NOT NULL,
    playback_session_id            UUID                     NOT NULL,
    stream_session_id              UUID                     NOT NULL,
    media_file_id                  UUID,
    note                           TEXT,
    requested_choice               TEXT                     NOT NULL,
    playback_choice                TEXT                     NOT NULL,
    capability_declaration         JSONB                    NOT NULL,
    quality_step_number            INTEGER,
    quality_step_count             INTEGER,
    quality_step_height            INTEGER,
    quality_step_bitrate           INTEGER,
    observed_bitrate               DOUBLE PRECISION,
    buffer_seconds                 DOUBLE PRECISION,
    dropped_frames                 INTEGER,
    stalls                         INTEGER,
    resolution_width               INTEGER,
    resolution_height              INTEGER,
    video_codec                    TEXT,
    hdr_mode                       TEXT,
    audio_codec                    TEXT,
    audio_channels                 INTEGER,
    audio_dolby_atmos              BOOLEAN,
    display_width                  INTEGER,
    display_height                 INTEGER,
    display_hdr_mode               TEXT,
    display_refresh_rate           DOUBLE PRECISION,
    display_dynamic_range_matched  BOOLEAN,
    display_frame_rate_matched     BOOLEAN,
    audio_output_route             TEXT,
    audio_output_channels          INTEGER,
    screen_width                   INTEGER,
    screen_height                  INTEGER,
    screen_pixel_ratio             DOUBLE PRECISION,
    screen_hdr                     BOOLEAN,
    browser_name                   TEXT,
    browser_version                TEXT,
    CONSTRAINT playback_report_pkey PRIMARY KEY (id),
    CONSTRAINT fk_playback_report_profile FOREIGN KEY (profile_id)
        REFERENCES profile (id) ON DELETE CASCADE,
    CONSTRAINT fk_playback_report_media_file FOREIGN KEY (media_file_id)
        REFERENCES media_file (id) ON DELETE SET NULL,
    CONSTRAINT chk_playback_report_note_length CHECK (char_length(note) BETWEEN 1 AND 280),
    CONSTRAINT chk_playback_report_quality_step CHECK (
        num_nulls(quality_step_number, quality_step_count, quality_step_height,
                  quality_step_bitrate) IN (0, 4)
        AND quality_step_number BETWEEN 1 AND quality_step_count),
    CONSTRAINT chk_playback_report_resolution CHECK (
        num_nulls(resolution_width, resolution_height) IN (0, 2)),
    CONSTRAINT chk_playback_report_audio CHECK (
        (audio_codec IS NULL AND audio_channels IS NULL AND audio_dolby_atmos IS NULL)
        OR (audio_codec IS NOT NULL AND audio_dolby_atmos IS NOT NULL)),
    CONSTRAINT chk_playback_report_display CHECK (
        num_nulls(display_width, display_height, display_hdr_mode, display_refresh_rate,
                  display_dynamic_range_matched, display_frame_rate_matched) IN (0, 6)),
    CONSTRAINT chk_playback_report_audio_output CHECK (
        num_nulls(audio_output_route, audio_output_channels) IN (0, 2)),
    CONSTRAINT chk_playback_report_screen CHECK (
        num_nulls(screen_width, screen_height, screen_pixel_ratio, screen_hdr) IN (0, 4)),
    CONSTRAINT chk_playback_report_browser CHECK (
        num_nulls(browser_name, browser_version) IN (0, 2))
);

CREATE INDEX idx_playback_report_shared_at ON playback_report (shared_at, id);

CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON playback_report
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();

-- The decision of each format attempt of the playback session, as the server held it when the
-- viewer shared the report. The audio columns are NULL together when the source has no audio.
-- reasons keeps the DecisionReason names in the decision's order.
CREATE TABLE playback_report_decision
(
    playback_report_id          UUID             NOT NULL,
    format_attempt              INTEGER          NOT NULL,
    stream_session_id           UUID             NOT NULL,
    video_delivery              TEXT             NOT NULL,
    video_source_codec          TEXT             NOT NULL,
    video_source_width          INTEGER          NOT NULL,
    video_source_height         INTEGER          NOT NULL,
    video_source_frame_rate     DOUBLE PRECISION,
    video_source_bitrate        BIGINT,
    video_source_range          TEXT             NOT NULL,
    video_source_dolby_vision   BOOLEAN          NOT NULL,
    video_output_codec          TEXT             NOT NULL,
    video_output_width          INTEGER          NOT NULL,
    video_output_height         INTEGER          NOT NULL,
    video_output_frame_rate     DOUBLE PRECISION,
    video_output_bitrate        BIGINT,
    video_output_range          TEXT             NOT NULL,
    video_output_dolby_vision   BOOLEAN          NOT NULL,
    audio_delivery              TEXT,
    audio_source_codec          TEXT,
    audio_source_channels       INTEGER,
    audio_source_bitrate        BIGINT,
    audio_output_codec          TEXT,
    audio_output_channels       INTEGER,
    audio_output_bitrate        BIGINT,
    reasons                     TEXT[]           NOT NULL,
    CONSTRAINT playback_report_decision_pkey PRIMARY KEY (playback_report_id, format_attempt),
    CONSTRAINT fk_playback_report_decision_report FOREIGN KEY (playback_report_id)
        REFERENCES playback_report (id) ON DELETE CASCADE,
    CONSTRAINT chk_playback_report_decision_video_delivery
        CHECK (video_delivery IN ('DIRECT_PLAY', 'COPY', 'ENCODE')),
    CONSTRAINT chk_playback_report_decision_audio_delivery
        CHECK (audio_delivery IN ('DIRECT_PLAY', 'COPY', 'ENCODE')),
    CONSTRAINT chk_playback_report_decision_audio CHECK (
        num_nulls(audio_delivery, audio_source_codec, audio_source_channels, audio_output_codec,
                  audio_output_channels) IN (0, 5)
        AND (audio_delivery IS NOT NULL
             OR (audio_source_bitrate IS NULL AND audio_output_bitrate IS NULL)))
);
