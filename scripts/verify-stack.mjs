import { execFileSync } from "node:child_process";
import { createConnection } from "node:net";

const infraOnly = process.argv.includes("--infra-only");
const compose = ["compose", "--env-file", "backend/.env"];
const requiredServices = [
    "postgres",
    "redis",
    "kafka",
    "kafka-2",
    "kafka-3",
    "minio",
    "clamav",
    "mailpit",
    ...(infraOnly ? [] : ["postgres-backup", "backend", "frontend"]),
];
const results = [];

const pause = (milliseconds) => new Promise((resolve) => {
    setTimeout(resolve, milliseconds);
});

const finalLine = (value) => value
    .toString()
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .at(-1);

const commandFailure = (reason) => finalLine(reason?.stderr || "")
    || finalLine(reason?.stdout || "")
    || reason?.message
    || "Check failed";

const execute = (command, args, timeout = 20_000) => execFileSync(command, args, {
    cwd: process.cwd(),
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    timeout,
}).trim();

const run = (name, purpose, command, args) => {
    const startedAt = Date.now();
    try {
        const output = execute(command, args);
        results.push({
            name,
            purpose,
            ready: true,
            detail: finalLine(output) || "Ready",
            latencyMs: Date.now() - startedAt,
        });
    } catch (reason) {
        results.push({
            name,
            purpose,
            ready: false,
            detail: commandFailure(reason),
            latencyMs: Date.now() - startedAt,
        });
    }
};

const runWithRetry = async (
    name,
    purpose,
    command,
    args,
    { attempts = 12, delayMs = 5_000 } = {},
) => {
    const startedAt = Date.now();
    let lastFailure = "Check failed";

    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        try {
            const output = execute(command, args);
            results.push({
                name,
                purpose,
                ready: true,
                detail: finalLine(output) || "Ready",
                latencyMs: Date.now() - startedAt,
            });
            return;
        } catch (reason) {
            lastFailure = commandFailure(reason);
            if (attempt < attempts) await pause(delayMs);
        }
    }

    results.push({
        name,
        purpose,
        ready: false,
        detail: lastFailure,
        latencyMs: Date.now() - startedAt,
    });
};

const requestWithRetry = async (
    name,
    purpose,
    url,
    { attempts = 12, delayMs = 5_000 } = {},
) => {
    const startedAt = Date.now();
    let lastFailure = "Request failed";

    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        try {
            const response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            results.push({
                name,
                purpose,
                ready: true,
                detail: `HTTP ${response.status}`,
                latencyMs: Date.now() - startedAt,
            });
            return;
        } catch (reason) {
            lastFailure = reason instanceof Error ? reason.message : "Request failed";
            if (attempt < attempts) await pause(delayMs);
        }
    }

    results.push({
        name,
        purpose,
        ready: false,
        detail: lastFailure,
        latencyMs: Date.now() - startedAt,
    });
};

const clamAvPing = () => new Promise((resolve, reject) => {
    const socket = createConnection({ host: "127.0.0.1", port: 3310 });
    let response = "";

    const fail = (reason) => {
        socket.destroy();
        reject(reason);
    };

    socket.setEncoding("utf8");
    socket.setTimeout(5_000);
    socket.once("connect", () => socket.write("PING\n"));
    socket.on("data", (chunk) => {
        response += chunk;
        if (response.includes("PONG")) {
            socket.end();
            resolve("PONG");
        }
    });
    socket.once("timeout", () => fail(new Error("TCP 3310 timed out")));
    socket.once("error", fail);
    socket.once("end", () => {
        if (!response.includes("PONG")) fail(new Error("ClamAV did not return PONG"));
    });
});

const clamAvWithRetry = async ({ attempts = 72, delayMs = 5_000 } = {}) => {
    const startedAt = Date.now();
    let lastFailure = "ClamAV TCP check failed";

    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        try {
            const detail = await clamAvPing();
            results.push({
                name: "ClamAV",
                purpose: "Document malware scanning",
                ready: true,
                detail: `${detail} from 127.0.0.1:3310`,
                latencyMs: Date.now() - startedAt,
            });
            return;
        } catch (reason) {
            lastFailure = reason instanceof Error ? reason.message : "ClamAV TCP check failed";
            if (attempt < attempts) await pause(delayMs);
        }
    }

    results.push({
        name: "ClamAV",
        purpose: "Document malware scanning",
        ready: false,
        detail: lastFailure,
        latencyMs: Date.now() - startedAt,
    });
};

run("Docker Compose", "Required containers", "docker", [
    ...compose,
    "ps",
    "--services",
    "--filter",
    "status=running",
]);

const running = (() => {
    try {
        return new Set(execute("docker", [
            ...compose,
            "ps",
            "--services",
            "--filter",
            "status=running",
        ], 10_000).split(/\s+/).filter(Boolean));
    } catch {
        return new Set();
    }
})();

const missing = requiredServices.filter((service) => !running.has(service));
if (missing.length) {
    results.push({
        name: "Compose services",
        purpose: "Container availability",
        ready: false,
        detail: `Not running: ${missing.join(", ")}`,
        latencyMs: 0,
    });
}

await Promise.all([
    runWithRetry("PostgreSQL", "Flyway and transactional data", "docker", [
        ...compose,
        "exec",
        "-T",
        "postgres",
        "sh",
        "-c",
        'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
    ]),
    runWithRetry("Redis", "OTP, rate limits and cache", "docker", [
        ...compose,
        "exec",
        "-T",
        "redis",
        "sh",
        "-c",
        'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping',
    ]),
    runWithRetry("Kafka", "Durable internal calls", "docker", [
        ...compose,
        "exec",
        "-T",
        "kafka",
        "/opt/kafka/bin/kafka-topics.sh",
        "--bootstrap-server",
        "localhost:29092",
        "--describe",
        "--topic",
        "brainserve.internal-calls.v1",
    ], { attempts: 24, delayMs: 5_000 }),
    clamAvWithRetry(),
    requestWithRetry(
        "MinIO",
        "Private employee documents",
        "http://localhost:9000/minio/health/ready",
    ),
    requestWithRetry(
        "Mailpit",
        "Development SMTP inbox",
        "http://localhost:8025/api/v1/info",
    ),
    ...(infraOnly ? [] : [
        requestWithRetry(
            "Spring Boot API",
            "Java service and migrations",
            "http://localhost:8080/actuator/health/readiness",
        ),
        requestWithRetry("Frontend", "Browser application", "http://localhost:3000"),
    ]),
]);

console.table(results);
const failures = results.filter((item) => !item.ready);

if (failures.length) {
    console.error(`Stack verification failed: ${failures.map((item) => item.name).join(", ")}`);
    process.exitCode = 1;
} else {
    console.log(infraOnly
        ? "BrainServe Connect infrastructure is ready for IntelliJ and Vite."
        : "BrainServe Connect full stack is ready.");
}
