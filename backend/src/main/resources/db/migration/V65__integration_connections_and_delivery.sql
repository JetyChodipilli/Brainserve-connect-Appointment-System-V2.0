CREATE TABLE integration_connection (
 id uuid PRIMARY KEY,
 request_id uuid NOT NULL UNIQUE,
 provider varchar(40) NOT NULL CHECK(provider IN ('SIMULATOR_CALENDAR','SIMULATOR_MESSAGING')),
 kind varchar(12) NOT NULL CHECK(kind IN ('CALENDAR','MESSAGING')),
 label varchar(80) NOT NULL,
 owner_id uuid NOT NULL REFERENCES iam_user_account(id),
 minimum_scopes jsonb NOT NULL CHECK(jsonb_typeof(minimum_scopes)='array'),
 status varchar(20) NOT NULL CHECK(status IN ('ACTIVE','NEEDS_RECONNECT','REVOKED')),
 credential_ciphertext text,
 credential_version bigint NOT NULL DEFAULT 1 CHECK(credential_version>0),
 credential_expires_at timestamptz NOT NULL,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 last_checked_at timestamptz,
 last_result_code varchar(40) NOT NULL DEFAULT 'NOT_CHECKED',
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(status='REVOKED' OR credential_ciphertext IS NOT NULL),
 CHECK(credential_ciphertext IS NULL OR length(credential_ciphertext)<=16384)
);
CREATE INDEX ix_integration_connection_status ON integration_connection(status,id);

CREATE TABLE integration_delivery (
 id uuid PRIMARY KEY,
 connection_id uuid NOT NULL REFERENCES integration_connection(id),
 business_event_id uuid NOT NULL,
 event_type varchar(30) NOT NULL CHECK(event_type IN ('APPOINTMENT_CREATED','APPOINTMENT_UPDATED','APPOINTMENT_CANCELLED','VISITOR_ARRIVED','CONNECTION_TEST')),
 resource_id uuid NOT NULL,
 business_revision bigint NOT NULL CHECK(business_revision>=0),
 occurred_at timestamptz NOT NULL,
 payload_json jsonb NOT NULL CHECK(jsonb_typeof(payload_json)='object'),
 scenario varchar(24) NOT NULL DEFAULT 'SUCCESS' CHECK(scenario IN ('SUCCESS','OUTAGE','RATE_LIMITED','REAUTH_REQUIRED','PERMANENT_FAILURE')),
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','DELIVERED','FAILED','NEEDS_RECONNECT','CANCELLED','SUPERSEDED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 total_attempts integer NOT NULL DEFAULT 0 CHECK(total_attempts BETWEEN 0 AND 20),
 manual_retries integer NOT NULL DEFAULT 0 CHECK(manual_retries BETWEEN 0 AND 3),
 retry_request_id uuid,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 lease_token uuid,
 lease_until timestamptz,
 claimed_credential_version bigint,
 last_result_code varchar(40) NOT NULL DEFAULT 'NOT_CHECKED',
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at timestamptz NOT NULL DEFAULT now(),
 delivered_at timestamptz,
 UNIQUE(connection_id,business_event_id)
);
CREATE INDEX ix_integration_delivery_due ON integration_delivery(next_attempt_at,id) WHERE status IN ('PENDING','RUNNING');
CREATE INDEX ix_integration_delivery_connection ON integration_delivery(connection_id,created_at DESC,id);

CREATE TABLE integration_delivery_attempt (
 id uuid PRIMARY KEY,
 delivery_id uuid NOT NULL REFERENCES integration_delivery(id),
 lease_token uuid NOT NULL UNIQUE,
 attempt_number integer NOT NULL CHECK(attempt_number>0),
 credential_version bigint NOT NULL,
 outcome varchar(40) NOT NULL,
 started_at timestamptz NOT NULL,
 completed_at timestamptz NOT NULL
);
CREATE TRIGGER immutable_integration_attempt BEFORE UPDATE OR DELETE ON integration_delivery_attempt
 FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();

CREATE TABLE integration_external_mapping (
 connection_id uuid NOT NULL REFERENCES integration_connection(id),
 resource_id uuid NOT NULL,
 external_id varchar(200) NOT NULL,
 business_revision bigint NOT NULL CHECK(business_revision>=0),
 business_event_id uuid NOT NULL,
 last_event_type varchar(30) NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(connection_id,resource_id)
);
-- The simulator persists provider-side idempotency receipts across process restarts.
CREATE TABLE integration_simulator_receipt (
 connection_id uuid NOT NULL REFERENCES integration_connection(id),
 business_event_id uuid NOT NULL,
 external_id varchar(200) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(connection_id,business_event_id)
);
CREATE TRIGGER immutable_integration_simulator_receipt BEFORE UPDATE OR DELETE ON integration_simulator_receipt
 FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();

-- A revision is allocated only inside the originating business transaction.
CREATE TABLE integration_resource_revision (
 resource_id uuid PRIMARY KEY,
 revision bigint NOT NULL CHECK(revision>0)
);
CREATE TABLE integration_business_event (
 id uuid PRIMARY KEY,
 resource_id uuid NOT NULL,
 business_revision bigint NOT NULL CHECK(business_revision>0),
 event_type varchar(30) NOT NULL CHECK(event_type IN ('APPOINTMENT_CREATED','APPOINTMENT_UPDATED','APPOINTMENT_CANCELLED','VISITOR_ARRIVED')),
 occurred_at timestamptz NOT NULL,
 payload_json jsonb NOT NULL CHECK(jsonb_typeof(payload_json)='object'),
 UNIQUE(resource_id,business_revision)
);
CREATE TRIGGER immutable_integration_business_event BEFORE UPDATE OR DELETE ON integration_business_event
 FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();
CREATE TABLE integration_command_receipt (
 request_id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES iam_user_account(id),
 operation varchar(12) NOT NULL CHECK(operation IN ('TEST','RETRY')),
 target_id uuid NOT NULL,
 expected_version bigint NOT NULL CHECK(expected_version>=0),
 scenario varchar(24),
 delivery_id uuid NOT NULL REFERENCES integration_delivery(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER immutable_integration_command_receipt BEFORE UPDATE OR DELETE ON integration_command_receipt
 FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();
CREATE UNIQUE INDEX ux_integration_delivery_revision ON integration_delivery(connection_id,resource_id,business_revision,event_type)
 WHERE event_type<>'CONNECTION_TEST';
ALTER TABLE integration_delivery ADD COLUMN lease_started_at timestamptz;
ALTER TABLE integration_delivery ADD CONSTRAINT ck_integration_delivery_lease
 CHECK((status='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND lease_started_at IS NOT NULL AND claimed_credential_version IS NOT NULL)
 OR (status<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL AND lease_started_at IS NULL AND claimed_credential_version IS NULL));

CREATE INDEX ix_integration_delivery_diagnostic_window ON integration_delivery(occurred_at);
CREATE INDEX ix_integration_business_event_occurred ON integration_business_event(occurred_at);
