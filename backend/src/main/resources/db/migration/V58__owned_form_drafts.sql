-- Account-owned allowlisted JSON only. No credentials or uploaded documents belong in drafts.
create table owned_form_draft (
    owner_id uuid not null references iam_user_account(id) on delete cascade,
    form_type varchar(32) not null check (form_type in ('TASK_CREATE','TASK_UPDATE','VISIT_INTAKE','COMPANY_PROFILE')),
    context_key varchar(100) not null,
    schema_version integer not null check (schema_version = 1),
    revision bigint not null check (revision > 0),
    scope_stamp varchar(64) not null,
    fields_ciphertext text not null check (octet_length(fields_ciphertext) <= 32000),
    submission_key uuid not null unique,
    submitted_at timestamptz,
    receipt jsonb,
    updated_at timestamptz not null,
    expires_at timestamptz not null,
    primary key (owner_id, form_type, context_key),
    check (expires_at > updated_at),
    check ((submitted_at is null and receipt is null) or (submitted_at is not null and receipt is not null))
);
create index owned_form_draft_expiry_idx on owned_form_draft(expires_at);
