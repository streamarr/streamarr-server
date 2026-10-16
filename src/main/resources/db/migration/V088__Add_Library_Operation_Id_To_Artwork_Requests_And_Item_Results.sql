-- An artwork request carries the id of the library operation that waits for it. A background
-- request, and a request from outside an operation that no operation took over, carries none.
-- An item result carries the id that its request carried when the result was saved, or the id of
-- the operation whose task saved it. The ids reference no row: an operation's row is deleted when
-- the operation ends, and the ids it left behind stay, because an operation reads only its own id.
ALTER TABLE artwork_request
    ADD COLUMN library_operation_id UUID;

ALTER TABLE item_result
    ADD COLUMN library_operation_id UUID;

CREATE INDEX artwork_request_library_operation_id_idx
    ON artwork_request (library_operation_id)
    WHERE library_operation_id IS NOT NULL;

CREATE INDEX item_result_library_operation_id_idx
    ON item_result (library_operation_id)
    WHERE library_operation_id IS NOT NULL;
