-- Durable, owner-authorized repair batches use current committed source snapshots at execution time.
ALTER TABLE integration_delivery DROP CONSTRAINT integration_delivery_event_type_check;
ALTER TABLE integration_delivery ADD CONSTRAINT integration_delivery_event_type_check
 CHECK(event_type IN ('APPOINTMENT_CREATED','APPOINTMENT_UPDATED','APPOINTMENT_CANCELLED','VISITOR_ARRIVED','CONNECTION_TEST','CALENDAR_RECONCILE'));
DROP INDEX ux_integration_delivery_revision;
CREATE UNIQUE INDEX ux_integration_delivery_revision ON integration_delivery(connection_id,resource_id,business_revision,event_type)
 WHERE event_type NOT IN ('CONNECTION_TEST','CALENDAR_RECONCILE');

CREATE TABLE integration_calendar_reconciliation (
 id uuid PRIMARY KEY,
 request_id uuid NOT NULL UNIQUE,
 connection_id uuid NOT NULL REFERENCES integration_connection(id),
 actor_id uuid NOT NULL REFERENCES iam_user_account(id),
 expected_version bigint NOT NULL CHECK(expected_version>=0),
 credential_version bigint NOT NULL CHECK(credential_version>0),
 status varchar(12) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
 processed integer NOT NULL DEFAULT 0 CHECK(processed BETWEEN 0 AND 500),
 cursor_resource_id uuid,
 scan_complete boolean NOT NULL DEFAULT false,
 next_batch_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,
 CHECK((status IN ('QUEUED','RUNNING') AND completed_at IS NULL) OR (status NOT IN ('QUEUED','RUNNING') AND completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_calendar_reconciliation_active ON integration_calendar_reconciliation(connection_id)
 WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ix_calendar_reconciliation_due ON integration_calendar_reconciliation(next_batch_at,id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ix_calendar_reconciliation_connection ON integration_calendar_reconciliation(connection_id,created_at DESC,id);
ALTER TABLE integration_delivery ADD COLUMN reconciliation_id uuid REFERENCES integration_calendar_reconciliation(id);
CREATE UNIQUE INDEX ux_calendar_reconciliation_resource ON integration_delivery(reconciliation_id,resource_id)
 WHERE reconciliation_id IS NOT NULL;
CREATE INDEX ix_calendar_reconciliation_deliveries ON integration_delivery(reconciliation_id,status) WHERE reconciliation_id IS NOT NULL;
CREATE INDEX ix_calendar_business_latest ON integration_business_event(resource_id,business_revision DESC)
 WHERE event_type<>'VISITOR_ARRIVED';
