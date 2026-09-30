CREATE TABLE internal_call_notification (
    id uuid PRIMARY KEY,
    sender_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    recipient_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    sender_name varchar(170) NOT NULL,
    recipient_name varchar(170) NOT NULL,
    message varchar(500) NOT NULL,
    delivery_status varchar(20) NOT NULL,
    sent_at timestamptz NOT NULL,
    delivered_at timestamptz,
    read_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_internal_call_not_self CHECK (sender_user_id <> recipient_user_id)
);

CREATE INDEX ix_internal_call_recipient ON internal_call_notification(recipient_user_id, sent_at DESC);
CREATE INDEX ix_internal_call_sender ON internal_call_notification(sender_user_id, sent_at DESC);
CREATE INDEX ix_internal_call_unread ON internal_call_notification(recipient_user_id, read_at, sent_at DESC);
