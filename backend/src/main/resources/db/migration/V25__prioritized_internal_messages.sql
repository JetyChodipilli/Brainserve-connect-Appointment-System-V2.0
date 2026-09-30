ALTER TABLE internal_call_notification
    ADD COLUMN priority varchar(20) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN category varchar(30) NOT NULL DEFAULT 'GENERAL',
    ADD COLUMN conversation_key varchar(73);

UPDATE internal_call_notification
   SET conversation_key = LEAST(sender_user_id::text, recipient_user_id::text)
       || ':' || GREATEST(sender_user_id::text, recipient_user_id::text)
 WHERE conversation_key IS NULL;

ALTER TABLE internal_call_notification
    ALTER COLUMN conversation_key SET NOT NULL,
    ADD CONSTRAINT ck_internal_call_priority CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT')),
    ADD CONSTRAINT ck_internal_call_category CHECK (category IN
        ('GENERAL', 'ACTION_REQUIRED', 'VISITOR', 'WORK', 'INSIGHT', 'LEAVE'));

CREATE INDEX ix_internal_call_priority_inbox
    ON internal_call_notification(recipient_user_id, read_at, priority, sent_at DESC);
CREATE INDEX ix_internal_call_conversation
    ON internal_call_notification(conversation_key, sent_at DESC);
