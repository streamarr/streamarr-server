-- The server cannot read version 1 and version 3 results correctly, because they lack the facts
-- that playback needs (streamarr/streamarr-server#501, ADR 0032). Deleting a result also deletes
-- its streams, keyframe positions and chapters.
--
-- The startup re-scan finds a file through its probe request or its result. A file whose source
-- went missing has no request but keeps its result, so that result's inputs become its request
-- before the result is deleted. An existing request stays as it is.
INSERT INTO media_file_probe_task_request (media_file_id,
                                           source_size,
                                           source_modified_epoch_second,
                                           source_modified_nanos,
                                           probe_version)
SELECT media_file_id, source_size, source_modified_epoch_second, source_modified_nanos, probe_version
FROM media_file_container_info
WHERE probe_version < 4
ON CONFLICT (media_file_id) DO NOTHING;

DELETE FROM media_file_container_info
WHERE probe_version < 4;
