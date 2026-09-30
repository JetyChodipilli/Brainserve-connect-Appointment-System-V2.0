CREATE TABLE iam_user_permission_grant (
    user_id uuid NOT NULL REFERENCES iam_user_account(id) ON DELETE CASCADE,
    permission_name varchar(80) NOT NULL,
    PRIMARY KEY (user_id, permission_name)
);

CREATE TABLE iam_user_permission_deny (
    user_id uuid NOT NULL REFERENCES iam_user_account(id) ON DELETE CASCADE,
    permission_name varchar(80) NOT NULL,
    PRIMARY KEY (user_id, permission_name)
);

CREATE TABLE stored_document (
    id uuid PRIMARY KEY,
    owner_type varchar(40) NOT NULL,
    owner_id uuid NOT NULL,
    category varchar(50) NOT NULL,
    object_key varchar(300) NOT NULL UNIQUE,
    original_filename varchar(255) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    sha256 varchar(64) NOT NULL,
    status varchar(20) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);
CREATE INDEX ix_document_owner ON stored_document(owner_type, owner_id, created_at DESC);
CREATE INDEX ix_document_sha256 ON stored_document(sha256);
