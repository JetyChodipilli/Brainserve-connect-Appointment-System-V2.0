ALTER TABLE integration_connection DROP CONSTRAINT integration_connection_provider_check;
ALTER TABLE integration_connection ADD CONSTRAINT integration_connection_provider_check
 CHECK(provider IN ('SIMULATOR_CALENDAR','SIMULATOR_MESSAGING','GOOGLE_CALENDAR','SLACK_MESSAGING'));
ALTER TABLE integration_delivery DROP CONSTRAINT integration_delivery_status_check;
ALTER TABLE integration_delivery ADD CONSTRAINT integration_delivery_status_check
 CHECK(status IN ('PENDING','RUNNING','DELIVERED','FAILED','NEEDS_RECONNECT','CANCELLED','SUPERSEDED','UNKNOWN'));

-- A dedicated bot prevents revoking one connection from silently disabling another.
CREATE TABLE integration_slack_destination (
 connection_id uuid PRIMARY KEY REFERENCES integration_connection(id),
 workspace_id varchar(32) NOT NULL CHECK(workspace_id ~ '^T[A-Z0-9]{8,31}$'),
 channel_id varchar(32) NOT NULL CHECK(channel_id ~ '^[CG][A-Z0-9]{8,31}$'),
 bot_id varchar(32) NOT NULL CHECK(bot_id ~ '^B[A-Z0-9]{8,31}$'),
 credential_fingerprint varchar(64) NOT NULL UNIQUE,
 revocation_status varchar(12) NOT NULL DEFAULT 'NONE' CHECK(revocation_status IN ('NONE','PENDING','RETRYING','FAILED','COMPLETE')),
 last_result_code varchar(40) NOT NULL DEFAULT 'NOT_CHECKED',
 UNIQUE(workspace_id,bot_id)
);
-- Empty channel ID is the method/workspace Retry-After fence; channel rows enforce one post per second.
CREATE TABLE integration_slack_rate_limit (
 workspace_id varchar(32) NOT NULL,
 channel_id varchar(32) NOT NULL,
 next_attempt_at timestamptz NOT NULL,
 PRIMARY KEY(workspace_id,channel_id)
);
CREATE TABLE integration_slack_revocation (
 id uuid PRIMARY KEY,
 connection_id uuid NOT NULL REFERENCES integration_slack_destination(connection_id),
 credential_version bigint NOT NULL CHECK(credential_version>0),
 token_ciphertext text NOT NULL CHECK(length(token_ciphertext)<=32768),
 status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RETRYING','FAILED','COMPLETE')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 10),
 manual_retries integer NOT NULL DEFAULT 0 CHECK(manual_retries BETWEEN 0 AND 3),
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 last_result_code varchar(40) NOT NULL DEFAULT 'REVOCATION_PENDING',
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(connection_id,credential_version),
 CHECK((status='COMPLETE')=(token_ciphertext=''))
);
CREATE INDEX ix_slack_revocation_due ON integration_slack_revocation(next_attempt_at,id) WHERE status IN ('PENDING','RETRYING');
