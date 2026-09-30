import { existsSync, writeFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { resolve } from "node:path";

const target = resolve("backend", ".env");
if (existsSync(target)) {
  console.error("backend/.env already exists. Keep it, or rename it only if you intentionally want a new local secret set.");
  process.exit(1);
}

const base64 = (bytes = 32) => randomBytes(bytes).toString("base64");
const token = (bytes = 24) => randomBytes(bytes).toString("base64url");
const adminPassword = `Bsc!${token(18)}9a`;

const content = `# Generated local environment for IntelliJ and Docker Compose.
# Never commit this file.

# PostgreSQL exposed by Docker Compose
DB_URL=jdbc:postgresql://127.0.0.1:5433/brainserve
DB_USERNAME=postgres
DB_PASSWORD=${token(30)}

# Redis exposed by Docker Compose
REDIS_HOST=127.0.0.1
REDIS_PORT=6380
REDIS_HOST_PORT=6380
REDIS_PASSWORD=${token(30)}
REDIS_TIMEOUT=2s

# Kafka exposed by Docker Compose
KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092,127.0.0.1:9094,127.0.0.1:9096
KAFKA_CONSUMER_GROUP=brainserve.internal-calls.v1
INTERNAL_CALL_TOPIC=brainserve.internal-calls.v1
INTERNAL_CALL_TOPIC_PARTITIONS=3
INTERNAL_CALL_TOPIC_REPLICAS=3
INTERNAL_CALL_TOPIC_MIN_INSYNC_REPLICAS=2

# Local Mailpit
MAIL_HOST=127.0.0.1
MAIL_PORT=1025
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_SMTP_AUTH=false
MAIL_STARTTLS=false
MAIL_FROM=noreply@brainserve.in

# IntelliJ backend plus Vite frontend
FRONTEND_PUBLIC_URL=http://localhost:5173
BRAIN_SERVE_ALLOWED_ORIGINS=http://localhost:5173
DOCKER_FRONTEND_PUBLIC_URL=http://localhost:3000
DOCKER_ALLOWED_ORIGINS=http://localhost:3000
FORWARD_HEADERS_STRATEGY=none
OFFICE_TIME_ZONE=Asia/Kolkata

# Required application secrets
JWT_SECRET=${base64(64)}
PII_ENCRYPTION_KEY=${base64(32)}
QR_PASS_SIGNING_SECRET=${base64(32)}

# System Admin bootstrap
SYSTEM_ADMIN_EMAIL=system.admin@brainserve.in
SYSTEM_ADMIN_DEFAULT_PASSWORD=${adminPassword}
SYSTEM_ADMIN_BOOTSTRAP_ENABLED=false

# CEO bootstrap
CEO_BOOTSTRAP_ENABLED=false
CEO_BOOTSTRAP_NAME=BrainServe CEO
CEO_BOOTSTRAP_EMAIL=ceo@brainserve.in
CEO_BOOTSTRAP_PASSWORD=

# Identity and retention
COMPANY_EMAIL_DOMAIN=brainserve.in
SUPPORT_EMAIL=support@brainserve.in
ACCOUNT_ARCHIVE_RETENTION_YEARS=7
EXPIRED_SESSION_RETENTION_DAYS=30
SESSION_RETENTION_CRON=0 25 3 * * *
ACCOUNT_ARCHIVE_OTP_MINUTES=10
ACCOUNT_ARCHIVE_OTP_RESEND_SECONDS=60
ACCOUNT_ARCHIVE_OTP_MAX_ATTEMPTS=5
ADMIN_PASSWORD_VERIFICATION_LOCK_MINUTES=15
ACCOUNT_CLOSURE_CRON=0 5 0 * * *

# Reporting and physical backup retention
ARCHIVE_ENCRYPTION_KEYS=v1=${base64(32)}
ARCHIVE_ENCRYPTION_ACTIVE_KEY_VERSION=v1
DASHBOARD_CACHE_SECONDS=180
REPORT_EXPORT_RETENTION_DAYS=7
REPORT_EXPORT_MAX_ROWS=1000000
PARTITION_ARCHIVE_ENABLED=true
PARTITION_CLEANUP_ENABLED=true
BACKUP_LIFECYCLE_RETENTION_DAYS=35
POSTGRES_BACKUP_INTERVAL_SECONDS=86400
POSTGRES_BACKUP_RETENTION_DAYS=14
POSTGRES_WAL_RETENTION_DAYS=35

# Local MinIO
S3_ENDPOINT=http://127.0.0.1:9000
S3_PUBLIC_ENDPOINT=http://127.0.0.1:9000
S3_REGION=ap-south-1
S3_ACCESS_KEY=brainserve${token(8)}
S3_SECRET_KEY=${token(36)}

# ClamAV
CLAMAV_HOST=127.0.0.1
CLAMAV_PORT=3310
`;

writeFileSync(target, content, { encoding: "utf8", mode: 0o600, flag: "wx" });
console.log("Created backend/.env with private local secrets.");
console.log(`Initial System Admin password: ${adminPassword}`);
console.log("Keep this password private. It is shown once and is also present in backend/.env.");

