CREATE INDEX IF NOT EXISTS ix_appointment_reception_monthly_register
    ON appointment(reception_verified_at)
    WHERE reception_verified_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_internal_call_delivery_retry
    ON internal_call_notification(delivery_status, sent_at)
    WHERE delivery_status IN ('QUEUED', 'FAILED');
