package com.brainserve.appointment.operations.application;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class IntegrationHealthService {
    private final JdbcTemplate database;
    private final StringRedisTemplate redis;
    private final JavaMailSender mail;
    private final S3Client objectStorage;
    private final String kafkaBootstrapServers;
    private final String kafkaTopic;
    private final String objectStorageBucket;
    private final String malwareScannerHost;
    private final int malwareScannerPort;

    public IntegrationHealthService(
            JdbcTemplate database,
            StringRedisTemplate redis,
            JavaMailSender mail,
            S3Client objectStorage,
            @Value("${spring.kafka.bootstrap-servers}") String kafkaBootstrapServers,
            @Value("${brainserve.notification.internal-call-topic}") String kafkaTopic,
            @Value("${brainserve.document.bucket}") String objectStorageBucket,
            @Value("${brainserve.document.clamav-host}") String malwareScannerHost,
            @Value("${brainserve.document.clamav-port}") int malwareScannerPort) {
        this.database = database;
        this.redis = redis;
        this.mail = mail;
        this.objectStorage = objectStorage;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.kafkaTopic = kafkaTopic;
        this.objectStorageBucket = objectStorageBucket;
        this.malwareScannerHost = malwareScannerHost;
        this.malwareScannerPort = malwareScannerPort;
    }

    public IntegrationOverview inspect() {
        List<ServiceStatus> services = new ArrayList<>();
        services.add(check("PostgreSQL", "Primary records and Flyway schema", this::databaseDetail));
        services.add(check("Redis", "OTP, rate limits and short-lived security state", this::redisDetail));
        services.add(check("Kafka", "Durable BrainServe Internal Calls delivery", this::kafkaDetail));
        services.add(check("SMTP", "Account, OTP and appointment email", this::mailDetail));
        services.add(check("Object storage", "Private employee documents and report exports", this::storageDetail));
        services.add(check("ClamAV", "Uploaded-file malware scanning", this::malwareDetail));
        boolean ready = services.stream().allMatch(ServiceStatus::ready);
        return new IntegrationOverview(ready ? "READY" : "DEGRADED", Instant.now(), List.copyOf(services));
    }

    private String databaseDetail() {
        Integer value = database.queryForObject("select 1", Integer.class);
        if (value == null || value != 1) throw new IllegalStateException("Unexpected database response");
        return "Connection and schema query succeeded";
    }

    private String redisDetail() {
        var factory = redis.getConnectionFactory();
        if (factory == null) throw new IllegalStateException("Redis connection factory is unavailable");
        try (var connection = factory.getConnection()) {
            String response = connection.ping();
            if (!"PONG".equalsIgnoreCase(response)) throw new IllegalStateException("Redis did not return PONG");
        }
        return "Connection and PING succeeded";
    }

    private String kafkaDetail() throws Exception {
        Map<String, Object> configuration = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2_000);
        try (Admin admin = Admin.create(configuration)) {
            String clusterId = admin.describeCluster().clusterId().get(2, TimeUnit.SECONDS);
            boolean topicExists = admin.listTopics().names().get(2, TimeUnit.SECONDS).contains(kafkaTopic);
            if (!topicExists) throw new IllegalStateException("Internal-calls topic is missing");
            return "Cluster " + clusterId + " · internal-calls topic ready";
        }
    }

    private String mailDetail() throws Exception {
        if (mail instanceof JavaMailSenderImpl sender) {
            sender.testConnection();
            return "SMTP connection succeeded";
        }
        return "Mail sender is configured";
    }

    private String storageDetail() {
        objectStorage.headBucket(HeadBucketRequest.builder().bucket(objectStorageBucket).build());
        return "Private bucket " + objectStorageBucket + " is reachable";
    }

    private String malwareDetail() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(malwareScannerHost, malwareScannerPort), 1_500);
            return "Scanner socket is reachable";
        } catch (Exception exception) {
            throw new IllegalStateException("ClamAV socket is unavailable", exception);
        }
    }

    private ServiceStatus check(String name, String purpose, CheckedSupplier supplier) {
        Instant started = Instant.now();
        try {
            String detail = supplier.get();
            return new ServiceStatus(name, purpose, true, detail,
                    Duration.between(started, Instant.now()).toMillis());
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
            return new ServiceStatus(name, purpose, false,
                    message.substring(0, Math.min(message.length(), 240)),
                    Duration.between(started, Instant.now()).toMillis());
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier {
        String get() throws Exception;
    }

    public record IntegrationOverview(String status, Instant checkedAt, List<ServiceStatus> services) {}
    public record ServiceStatus(String name, String purpose, boolean ready, String detail, long latencyMs) {}
}
