-- Bounded employee pages and append-only audit/log cursor scans.
create index if not exists idx_employee_department_status_name_id
    on employee (department_id, status, lower(display_name), id);
create index if not exists idx_employee_status_name_id
    on employee (status, lower(display_name), id);

create index if not exists idx_audit_event_occurred_id_desc
    on audit_event (occurred_at desc, id desc);
create index if not exists idx_audit_event_outcome_occurred_id_desc
    on audit_event (outcome, occurred_at desc, id desc);
create index if not exists idx_audit_event_type_lower
    on audit_event (lower(event_type));

create index if not exists idx_essential_log_occurred_id_desc
    on essential_log_record (occurred_at desc, id desc);
create index if not exists idx_essential_log_category_occurred_id_desc
    on essential_log_record (category, occurred_at desc, id desc);
create index if not exists idx_essential_log_status_occurred_id_desc
    on essential_log_record (status, occurred_at desc, id desc);
