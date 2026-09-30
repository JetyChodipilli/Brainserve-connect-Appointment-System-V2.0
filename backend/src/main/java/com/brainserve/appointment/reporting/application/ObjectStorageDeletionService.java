package com.brainserve.appointment.reporting.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.List;

@Service
public class ObjectStorageDeletionService {
    private final S3Client s3;
    private final String bucket;

    public ObjectStorageDeletionService(S3Client s3,
                                        @Value("${brainserve.document.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public void deleteEveryVersion(String objectKey) {
        String keyMarker = null;
        String versionMarker = null;
        boolean listedAny = false;
        do {
            ListObjectVersionsResponse page = s3.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(bucket).prefix(objectKey).keyMarker(keyMarker)
                    .versionIdMarker(versionMarker).build());
            List<ObjectIdentifier> objects = new ArrayList<>();
            page.versions().stream().filter(item -> item.key().equals(objectKey))
                    .forEach(item -> objects.add(ObjectIdentifier.builder()
                            .key(item.key()).versionId(item.versionId()).build()));
            page.deleteMarkers().stream().filter(item -> item.key().equals(objectKey))
                    .forEach(item -> objects.add(ObjectIdentifier.builder()
                            .key(item.key()).versionId(item.versionId()).build()));
            if (!objects.isEmpty()) {
                listedAny = true;
                s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket)
                        .delete(Delete.builder().objects(objects).quiet(true).build()).build());
            }
            if (!Boolean.TRUE.equals(page.isTruncated())) break;
            keyMarker = page.nextKeyMarker();
            versionMarker = page.nextVersionIdMarker();
        } while (true);
        if (!listedAny) {
            s3.deleteObject(request -> request.bucket(bucket).key(objectKey));
        }
    }

    public boolean isGone(String objectKey) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return false;
        } catch (S3Exception ex) {
            return ex.statusCode() == 404;
        }
    }
}
