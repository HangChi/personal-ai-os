package com.personalos.chat.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@ConditionalOnProperty(name="app.storage.s3.enabled",havingValue="true")
public class S3StorageConfig {
    @Bean(destroyMethod="close") S3Client attachmentS3Client(@Value("${app.storage.s3.endpoint}") URI endpoint,
            @Value("${app.storage.s3.region:us-east-1}") String region,
            @Value("${app.storage.s3.access-key:}") String access,
            @Value("${app.storage.s3.secret-key:}") String secret) {
        if (!java.util.Set.of("http","https").contains(endpoint.getScheme()) || endpoint.getUserInfo()!=null)
            throw new IllegalArgumentException("S3 endpoint 不合法");
        if (access.isBlank()!=secret.isBlank()) throw new IllegalArgumentException("S3 access-key 与 secret-key 必须同时配置");
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(access.isBlank()?DefaultCredentialsProvider.builder().build():StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret)))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5)))
                .build();
    }
    @Bean AttachmentObjectStore attachmentObjectStore(S3Client client,@Value("${app.storage.s3.bucket}") String bucket,
            @Value("${app.storage.s3.namespace}") String namespace,@Value("${app.storage.s3.cleanup:false}") boolean cleanup,
            @Value("${app.chat.max-attachment-bytes:6291456}") long maximum) {
        return new S3AttachmentStore(client,bucket,namespace,cleanup,maximum);
    }
}
