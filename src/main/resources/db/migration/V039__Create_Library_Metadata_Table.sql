CREATE TYPE alphabet_letter AS ENUM (
  'A','B','C','D','E','F','G','H','I','J','K','L','M',
  'N','O','P','Q','R','S','T','U','V','W','X','Y','Z',
  'HASH'
);

CREATE TABLE library_metadata (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  library_id UUID NOT NULL REFERENCES library(id) ON DELETE CASCADE,
  letter alphabet_letter NOT NULL,
  item_count INTEGER NOT NULL DEFAULT 0,
  UNIQUE (library_id, letter)
);

CREATE INDEX idx_library_metadata_library_id ON library_metadata(library_id);
