UPDATE library
SET filepath_uri = 'file://' || filepath_uri
WHERE filepath_uri NOT LIKE 'file://%';
