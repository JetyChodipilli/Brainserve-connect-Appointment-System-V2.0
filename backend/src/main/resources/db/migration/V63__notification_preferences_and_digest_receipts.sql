CREATE TABLE notification_preference (
 user_id uuid PRIMARY KEY REFERENCES iam_user_account(id),
 version bigint NOT NULL DEFAULT 0,
 in_app_enabled boolean NOT NULL DEFAULT true,
 email_enabled boolean NOT NULL DEFAULT false,
 sound_enabled boolean NOT NULL DEFAULT true,
 cadence varchar(12) NOT NULL DEFAULT 'IMMEDIATE' CHECK(cadence IN ('IMMEDIATE','HOURLY','DAILY')),
 zone_id varchar(80) NOT NULL DEFAULT 'Asia/Kolkata',
 quiet_enabled boolean NOT NULL DEFAULT false,
 quiet_start time NOT NULL DEFAULT '22:00',
 quiet_end time NOT NULL DEFAULT '08:00',
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(NOT quiet_enabled OR quiet_start<>quiet_end)
);
ALTER TABLE internal_call_notification ADD COLUMN delivery_due_at timestamptz,
 ADD COLUMN preference_version bigint,
 ADD COLUMN mandatory boolean NOT NULL DEFAULT false;
UPDATE internal_call_notification SET mandatory=(priority='URGENT' OR category IN ('ACTION_REQUIRED','VISITOR','LEAVE') OR (priority='HIGH' AND category IN ('WORK','INSIGHT')));
CREATE TABLE notification_email_receipt (
 notification_id uuid PRIMARY KEY REFERENCES internal_call_notification(id),
 event_key varchar(160) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notification_email_event ON notification_email_receipt(event_key);
CREATE TRIGGER immutable_notification_email_receipt BEFORE UPDATE OR DELETE ON notification_email_receipt
 FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();
CREATE INDEX ix_notification_delivery_day ON internal_call_notification(recipient_user_id,delivered_at DESC) WHERE delivery_status='DELIVERED';
CREATE TABLE notification_preference_history (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES iam_user_account(id),
 version bigint NOT NULL,
 snapshot_json jsonb NOT NULL,
 changed_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,version)
);
CREATE TRIGGER immutable_notification_preference_history BEFORE UPDATE OR DELETE ON notification_preference_history
 FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();
