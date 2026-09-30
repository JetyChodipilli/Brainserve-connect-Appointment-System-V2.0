import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(path, "utf8");

const compose = read("docker-compose.yml");
const properties = read("backend/src/main/resources/application.properties");
const topicConfiguration = read(
    "backend/src/main/java/com/brainserve/appointment/notification/config/InternalNotificationKafkaConfiguration.java",
);

test("local Kafka uses a three-node KRaft quorum", () => {
    assert.match(compose, /KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093,2@kafka-2:9093,3@kafka-3:9093/);
    assert.match(compose, /KAFKA_NODE_ID: 1/);
    assert.match(compose, /KAFKA_NODE_ID: 2/);
    assert.match(compose, /KAFKA_NODE_ID: 3/);
    assert.match(compose, /KAFKA_BOOTSTRAP_SERVERS: kafka:29092,kafka-2:29092,kafka-3:29092/);
});

test("Kafka topics and internal state survive one broker failure", () => {
    assert.equal((compose.match(/KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3/g) ?? []).length, 3);
    assert.equal((compose.match(/KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 3/g) ?? []).length, 3);
    assert.equal((compose.match(/KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 2/g) ?? []).length, 3);
    assert.match(properties, /internal-call-topic-replicas=\$\{INTERNAL_CALL_TOPIC_REPLICAS:3\}/);
    assert.match(properties, /internal-call-topic-min-insync-replicas=\$\{INTERNAL_CALL_TOPIC_MIN_INSYNC_REPLICAS:2\}/);
    assert.match(topicConfiguration, /TopicConfig\.MIN_IN_SYNC_REPLICAS_CONFIG/);
});

test("Kafka producer waits for all in-sync replicas and is idempotent", () => {
    assert.match(properties, /spring\.kafka\.producer\.acks=all/);
    assert.match(properties, /spring\.kafka\.producer\.properties\.enable\.idempotence=true/);
    assert.match(properties, /spring\.kafka\.consumer\.properties\.allow\.auto\.create\.topics=false/);
});
