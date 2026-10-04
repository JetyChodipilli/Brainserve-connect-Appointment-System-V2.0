-- Literal contains queries use domain-owned projections; no PII identity fields enter an index.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_search_appointment_text ON appointment USING gin
    (lower(reference_number || ' ' || visitor_name || ' ' || coalesce(visitor_company,'') || ' ' || purpose) gin_trgm_ops);
CREATE INDEX ix_search_appointment_department ON appointment(routing_department_id,id);
CREATE INDEX ix_search_employee_text ON employee USING gin
    (lower(display_name || ' ' || employee_number || ' ' || designation) gin_trgm_ops) WHERE status='ACTIVE';
CREATE INDEX ix_search_employee_department ON employee(department_id,id) WHERE status='ACTIVE';
CREATE INDEX ix_search_visitor_text ON visitor USING gin
    (lower(name || ' ' || coalesce(company,'')) gin_trgm_ops) WHERE NOT restricted;
CREATE INDEX ix_search_task_text ON department_work_task USING gin
    (lower(title || ' ' || description || ' ' || department_branch) gin_trgm_ops);
CREATE INDEX ix_search_task_department ON department_work_task(department_id,employee_id,id);
CREATE INDEX ix_search_oversight_text ON work_task_audit_record USING gin
    (lower(task_title || ' ' || department_name) gin_trgm_ops)
    WHERE audit_status IN ('PENDING_CEO_APPROVAL','CEO_APPROVED','CEO_REWORK_REQUESTED');
