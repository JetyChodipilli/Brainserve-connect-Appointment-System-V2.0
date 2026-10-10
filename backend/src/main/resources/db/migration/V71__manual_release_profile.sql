-- Private descriptive metadata stays outside the legacy generic settings APIs, including during application rollback.
CREATE TABLE release_profile (
 id uuid PRIMARY KEY CHECK (id = '00000000-0000-0000-0000-000000000271'::uuid),
 profile_json varchar(2000) NOT NULL CHECK (jsonb_typeof(profile_json::jsonb) = 'object'
   AND coalesce(profile_json::jsonb->>'status' IN ('UNCONFIGURED','PILOT','ACTIVE','PAUSED','CANCELLED'),false)),
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL, created_by varchar(120) NOT NULL,
 updated_at timestamptz NOT NULL, updated_by varchar(120) NOT NULL
);
INSERT INTO release_profile(id,profile_json,version,created_at,created_by,updated_at,updated_by)
VALUES ('00000000-0000-0000-0000-000000000271',
 '{"status":"UNCONFIGURED","reference":"","startsOn":null,"renewsOn":null,"supportOwner":"","supportEmail":"","supportHours":""}',
 0,now(),'flyway',now(),'flyway');
