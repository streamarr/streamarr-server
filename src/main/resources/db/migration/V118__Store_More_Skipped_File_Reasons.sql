-- A scan also skips a file whose extension is not a supported video type (and that the ignore list
-- does not ignore), and an entry whose attributes it cannot read, and stores each skip as it stores
-- a soft link.
ALTER TYPE skipped_file_reason ADD VALUE 'UNSUPPORTED_FILE_TYPE';
ALTER TYPE skipped_file_reason ADD VALUE 'UNREADABLE';
