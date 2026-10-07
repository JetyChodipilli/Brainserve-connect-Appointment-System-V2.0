-- Tiny immutable request receipts survive payload expiry so a lost-response retry cannot renew an export.
CREATE TABLE support_diagnostic_request (
 request_id uuid PRIMARY KEY,
 owner_id uuid NOT NULL REFERENCES iam_user_account(id),
 requested_hours integer NOT NULL CHECK(requested_hours BETWEEN 1 AND 24),
 created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 CHECK(expires_at>created_at AND expires_at<=created_at+interval '24 hours')
);
CREATE TRIGGER immutable_support_diagnostic_request BEFORE UPDATE OR DELETE ON support_diagnostic_request
 FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();

CREATE TABLE support_diagnostic_package (
 id uuid PRIMARY KEY,
 request_id uuid NOT NULL UNIQUE REFERENCES support_diagnostic_request(request_id),
 owner_id uuid NOT NULL REFERENCES iam_user_account(id),
 requested_hours integer NOT NULL CHECK(requested_hours BETWEEN 1 AND 24),
 snapshot_json jsonb NOT NULL CHECK(jsonb_typeof(snapshot_json)='object' AND octet_length(snapshot_json::text)<=65536),
 size_bytes integer NOT NULL CHECK(size_bytes BETWEEN 1 AND 65536),
 window_start timestamptz NOT NULL,
 window_end timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL,
 download_count integer NOT NULL DEFAULT 0 CHECK(download_count BETWEEN 0 AND 1000000),
 CHECK(window_end>window_start AND window_end<=window_start+interval '24 hours'),
 CHECK(expires_at>created_at AND expires_at<=created_at+interval '24 hours')
);
CREATE INDEX ix_support_diagnostic_owner ON support_diagnostic_package(owner_id,created_at DESC,id);
CREATE INDEX ix_support_diagnostic_expiry ON support_diagnostic_package(expires_at);
