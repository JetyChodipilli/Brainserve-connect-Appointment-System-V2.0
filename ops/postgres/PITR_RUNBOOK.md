# PostgreSQL backup and point-in-time recovery

BrainServe keeps two independent recovery assets:

- Daily compressed physical base backups in the `postgres_backups` volume.
- Continuously archived WAL segments in the `postgres_wal_archive` volume.

The backup container verifies every base backup with SHA-256 and retains 14 days by default. Archived WAL is retained for 35 days by default and can never be configured below the base-backup retention window. These lifecycle windows ensure data disposed by the retention service also ages out of recoverable backups instead of surviving indefinitely. Store copies of both volumes in encrypted off-site storage for disaster recovery; a volume on the same host is not a complete backup strategy.

## Routine verification

1. Confirm the `postgres-backup` container is healthy and a recent `base-*` directory exists.
2. Confirm new files continue appearing in the WAL archive.
3. Once per month, restore the latest backup to an isolated PostgreSQL instance.
4. Run application smoke tests and record the achieved recovery-point and recovery-time objectives.
5. Confirm `backup_expiry_register` entries older than the configured backup
   lifecycle are marked `CONFIRMED` in Data Governance.

## Retention and legal holds

- Never restore an expired backup into a production-accessible environment.
- A legal hold protects live rows and cold archives. Backup media is governed by
  the backup lifecycle because physical PostgreSQL backups cannot selectively
  remove one person or case.
- When a restore contains data already disposed in production, keep the restore
  isolated and rerun the retention worker before any application access.
- Keep `POSTGRES_WAL_RETENTION_DAYS` greater than or equal to
  `POSTGRES_BACKUP_RETENTION_DAYS`; the backup worker refuses unsafe settings.

## Point-in-time recovery

1. Stop application writes and record the desired UTC recovery time.
2. Copy one base backup and the WAL archive to an isolated recovery host.
3. Run `ops/postgres/restore-pitr.sh <base-backup> <empty-target> '<UTC time>'`.
4. Mount the prepared target as PostgreSQL's data directory and mount the WAL archive at `/var/lib/postgresql/wal-archive` read-only.
5. Start PostgreSQL. It replays WAL until the target time and then promotes itself.
6. Validate Flyway history, row counts, recent visitor/checkpoint records and account access before redirecting the application.

Never test a restore against the live production data directory. Keep database credentials, encryption keys and S3 backups in the same disaster-recovery plan; database restoration alone cannot decrypt PII or recover private documents.
