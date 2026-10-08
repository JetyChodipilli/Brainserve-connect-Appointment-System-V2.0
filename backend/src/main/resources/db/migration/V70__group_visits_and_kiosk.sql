CREATE TABLE appointment_visit_group (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES iam_user_account(id),
 request_id uuid NOT NULL, payload_hash varchar(64) NOT NULL, label varchar(120) NOT NULL,
 host_employee_id uuid NOT NULL REFERENCES employee(id), type varchar(40) NOT NULL,
 slot_start timestamptz NOT NULL, slot_end timestamptz NOT NULL,
 member_count integer NOT NULL CHECK (member_count BETWEEN 2 AND 50),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(owner_id, request_id), CHECK(slot_end > slot_start)
);
ALTER TABLE appointment ADD COLUMN visit_group_id uuid REFERENCES appointment_visit_group(id);
CREATE INDEX ix_appointment_visit_group ON appointment(visit_group_id) WHERE visit_group_id IS NOT NULL;
ALTER TABLE appointment DROP CONSTRAINT ex_appointment_host_slot;
ALTER TABLE appointment ADD CONSTRAINT ex_appointment_host_slot
 EXCLUDE USING gist (host_employee_id WITH =, tstzrange(slot_start,slot_end,'[)') WITH &&,
 (coalesce(visit_group_id,id)) WITH <>)
 WHERE (status IN ('PENDING_VERIFICATION','PENDING_SECURITY_INTAKE','PENDING_RECEPTION_VERIFICATION',
 'PENDING_APPROVAL','PENDING_HR_APPROVAL','PENDING_TEAM_LEAD_APPROVAL','PENDING_MANAGER_APPROVAL',
 'PENDING_CEO_APPROVAL','APPROVED','RESCHEDULED','CHECKED_IN','IN_MEETING'));
-- A group reservation cannot be attached to a different host, time or visit type.
CREATE FUNCTION guard_visit_group_member() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND OLD.visit_group_id IS NOT NULL AND
 (NEW.visit_group_id IS DISTINCT FROM OLD.visit_group_id OR NEW.host_employee_id IS DISTINCT FROM OLD.host_employee_id
 OR NEW.slot_start IS DISTINCT FROM OLD.slot_start OR NEW.slot_end IS DISTINCT FROM OLD.slot_end OR NEW.type IS DISTINCT FROM OLD.type) THEN
 RAISE EXCEPTION 'Group reservation is immutable; cancel and preregister again'; END IF;
 IF NEW.visit_group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM appointment_visit_group g WHERE g.id=NEW.visit_group_id
 AND g.host_employee_id=NEW.host_employee_id AND g.type=NEW.type AND g.slot_start=NEW.slot_start AND g.slot_end=NEW.slot_end) THEN
 RAISE EXCEPTION 'Group member must match the group reservation'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER appointment_group_guard BEFORE INSERT OR UPDATE ON appointment FOR EACH ROW EXECUTE FUNCTION guard_visit_group_member();
CREATE TABLE kiosk_device (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES iam_user_account(id), label varchar(80) NOT NULL,
 token_hash varchar(64) NOT NULL UNIQUE, expires_at timestamptz NOT NULL, revoked_at timestamptz,
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE kiosk_arrival_intake (
 id uuid PRIMARY KEY, device_id uuid NOT NULL REFERENCES kiosk_device(id),
 appointment_id uuid NOT NULL UNIQUE REFERENCES appointment(id), received_at timestamptz NOT NULL DEFAULT now(),
 resolved_at timestamptz, resolved_by uuid REFERENCES iam_user_account(id), version bigint NOT NULL DEFAULT 0,
 CHECK ((resolved_at IS NULL) = (resolved_by IS NULL))
);
CREATE INDEX ix_kiosk_pending ON kiosk_arrival_intake(received_at,id) WHERE resolved_at IS NULL;
