-- CEO visits use one authoritative approval route:
-- Security -> Reception -> assigned department Manager -> company CEO.
-- Existing databases retain V1-V41 unchanged; this migration reconciles only
-- actionable future visits created under the earlier Manager-final workflow.

UPDATE appointment visit
   SET status = 'PENDING_MANAGER_APPROVAL',
       updated_at = now(),
       updated_by = 'flyway-v42',
       version = version + 1
 WHERE visit.type = 'CEO_VISIT'
   AND visit.status IN ('PENDING_HR_APPROVAL', 'PENDING_CEO_APPROVAL')
   AND visit.manager_decision_at IS NULL
   AND visit.slot_end > now()
   AND EXISTS (
       SELECT 1
         FROM department_manager_assignment assignment
        WHERE assignment.department_id = visit.routing_department_id
          AND assignment.active
   );

UPDATE appointment visit
   SET status = 'PENDING_CEO_APPROVAL',
       approval_actor_id = NULL,
       decision_at = NULL,
       decision_remarks = NULL,
       updated_at = now(),
       updated_by = 'flyway-v42',
       version = version + 1
 WHERE visit.type = 'CEO_VISIT'
   AND visit.status = 'APPROVED'
   AND visit.manager_decision_at IS NOT NULL
   AND visit.ceo_decision_at IS NULL
   AND visit.reception_forwarded_at IS NULL
   AND visit.slot_end > now();

UPDATE system_setting
   SET setting_value = 'true',
       description = 'CEO visits require the company CEO final decision after department Manager approval',
       updated_at = now(),
       updated_by = 'flyway-v42',
       version = version + 1
 WHERE setting_key = 'APPROVAL.CEO_VISIT.REQUIRES_CEO_AFTER_HR';
