ALTER TABLE internal_call_notification
    ADD COLUMN archived_at timestamptz,
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_by_user_id uuid REFERENCES iam_user_account(id);

UPDATE internal_call_notification
SET archived_at = sent_at
WHERE sent_at < date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata';

CREATE INDEX ix_internal_call_active_recipient
    ON internal_call_notification(recipient_user_id, sent_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_internal_call_active_sender
    ON internal_call_notification(sender_user_id, sent_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_internal_call_deleted
    ON internal_call_notification(deleted_at DESC)
    WHERE deleted_at IS NOT NULL;
