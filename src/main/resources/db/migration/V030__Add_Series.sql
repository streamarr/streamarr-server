INSERT INTO library (id, created_by, filepath, name, status, backend, type)
VALUES ('9bb6e1a9-eac1-40bf-9d07-30d2575e362a', 'cb46514c-04f8-4153-815d-fa044a4bf65e', '/mpool/media/shows',
        'TV Shows', 'HEALTHY', 'LOCAL', 'SERIES');


INSERT INTO base_collectable (id, created_by, title, library_id)
VALUES ('1e50a617-8ad3-4d11-b1bc-5ad9a8b7c672', 'cb46514c-04f8-4153-815d-fa044a4bf65e', 'Stranger Things',
        '9bb6e1a9-eac1-40bf-9d07-30d2575e362a');


INSERT INTO series (id, artwork, content_rating)
VALUES ('1e50a617-8ad3-4d11-b1bc-5ad9a8b7c672', '/path/art2.png', 'TV-M');
