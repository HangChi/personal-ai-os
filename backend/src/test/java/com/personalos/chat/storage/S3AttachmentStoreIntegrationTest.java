package com.personalos.chat.storage;

import static org.assertj.core.api.Assertions.*;
import com.personalos.chat.service.ChatAttachmentService;
import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.common.json.JsonCodec;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class S3AttachmentStoreIntegrationTest {
    @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.51"))
            .withCommand("mini","-dir=/data").withEnv("AWS_ACCESS_KEY_ID","test-access")
            .withEnv("AWS_SECRET_ACCESS_KEY","test-secret-password").withEnv("S3_BUCKET","personalos").withExposedPorts(8333);
    @TempDir Path directory;
    @Test void authenticatedStoragePreservesLocalFilesChecksSizesAndOnlyPrunesOwnedUnreferencedObjects() throws Exception {
        try(var client=S3Client.builder().endpointOverride(URI.create("http://"+S3.getHost()+":"+S3.getMappedPort(8333)))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access","test-secret-password"))).build()) {
            var store=new S3AttachmentStore(client,"personalos","test-owner",true,1000);
            var beans=new DefaultListableBeanFactory(); beans.registerSingleton("store",store);
            var service=new ChatAttachmentService(new JsonCodec(new ObjectMapper()),directory.toString(),1000,beans.getBeanProvider(AttachmentObjectStore.class));
            var uploaded=service.store(new MockMultipartFile("file","private.txt","text/plain","hello".getBytes()));
            assertThat(service.readBytes(uploaded.id())).isEqualTo("hello".getBytes());
            assertThat(service.verify(List.of(new ChatAttachmentDto(uploaded.id(),"private.txt","text/plain",999))).getFirst().size()).isEqualTo(5);
            String legacy=UUID.randomUUID().toString(); Files.writeString(directory.resolve(legacy),"local");
            assertThat(service.readBytes(legacy)).isEqualTo("local".getBytes());
            String protectedId=UUID.randomUUID().toString(); store.put(protectedId,"keep".getBytes(),"text/plain");
            String otherId=UUID.randomUUID().toString();
            var other=new S3AttachmentStore(client,"personalos","other-owner",false,1000); other.put(otherId,"other".getBytes(),"text/plain");
            assertThat(store.prune(Set.of(protectedId),Instant.now().plusSeconds(5))).isEqualTo(1);
            assertThat(store.size(uploaded.id())).isNull(); assertThat(store.size(protectedId)).isEqualTo(4);
            assertThat(other.size(otherId)).isEqualTo(5);
            assertThatThrownBy(()->service.load("../../etc/passwd")).isInstanceOf(com.personalos.common.exception.BusinessException.class);
            service.delete(legacy); assertThat(Files.exists(directory.resolve(legacy))).isFalse();
            store.delete(protectedId); other.delete(otherId);
        }
    }
}
