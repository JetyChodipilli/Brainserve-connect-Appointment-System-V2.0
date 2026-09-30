# BrainServe local configuration and GitHub guide

## 1. Configure the backend in one file

Edit `backend/src/main/resources/application.properties`. Its defaults target locally installed services:

```properties
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/brainserve}
spring.datasource.username=${DB_USERNAME:brainserve}
spring.datasource.password=${DB_PASSWORD:}
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

You can either add private local defaults after each colon or define the corresponding operating-system environment variables. Configure the System Admin/CEO bootstrap passwords, JWT, encryption and QR-signing secrets before startup. No working password or cryptographic secret is committed to GitHub.

The System Admin and CEO seeders are idempotent: they create missing accounts once and never overwrite a stored password or existing account.

## 2. Start with Maven and the local frontend

Start PostgreSQL, Redis and Kafka, then run:

```bash
cd backend
mvn clean test
mvn spring-boot:run
```

In a second terminal from the project root:

```bash
npm ci
npm run dev:backend
```

`dev:backend` connects the frontend to `http://localhost:8080/api/v1`, so no frontend configuration change is required for the normal local ports.

## 3. Optional Docker setup

```bash
docker compose up --build
```

Docker Compose overrides the same properties with container service names and starts PostgreSQL, Redis, Kafka, Mailpit, MinIO and ClamAV.

## 4. Give Codex access to an empty GitHub repository

1. In GitHub, create a new **empty** repository. A private repository is recommended. Do not add a README, `.gitignore` or license because this project already contains the repository files.
2. In ChatGPT, open **Settings → Apps/Connectors → GitHub**, select **Connect**, and authorize the GitHub app.
3. When GitHub asks for repository access, choose **Only select repositories** and select the empty BrainServe repository.
4. Return to this conversation and send the repository URL, for example `https://github.com/your-name/brainserve-appointment-service`.

Codex can then push the complete main branch. Never paste a personal access token or GitHub password into chat.

## 5. Manual push alternative

If you prefer to push from your computer after extracting the ZIP:

```bash
git init
git add .
git commit -m "Initial BrainServe appointment system"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPOSITORY.git
git push -u origin main
```

The repository includes GitHub Actions for frontend lint/build/tests and backend compilation/tests. Do not commit real production passwords or encryption keys; set them through environment variables or your deployment platform.
