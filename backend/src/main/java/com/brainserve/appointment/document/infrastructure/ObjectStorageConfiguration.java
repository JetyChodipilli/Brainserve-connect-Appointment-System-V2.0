package com.brainserve.appointment.document.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class ObjectStorageConfiguration {
    @Bean
    StaticCredentialsProvider s3Credentials(@Value("${aws.s3.access-key}") String accessKey,
                                            @Value("${aws.s3.secret-key}") String secretKey) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    @Bean
    S3Client s3Client(@Value("${aws.s3.endpoint}") URI endpoint, @Value("${aws.s3.region}") String region,
                      StaticCredentialsProvider credentials) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region)).credentialsProvider(credentials)
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    @Bean
    S3Presigner s3Presigner(@Value("${aws.s3.public-endpoint}") URI publicEndpoint,
                            @Value("${aws.s3.region}") String region,
                            StaticCredentialsProvider credentials) {
        return S3Presigner.builder().endpointOverride(publicEndpoint).region(Region.of(region)).credentialsProvider(credentials)
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }
}
