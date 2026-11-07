package com.personalos.chat.storage;
import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import org.junit.jupiter.api.Test;
class S3StorageConfigTest {
    @Test void validatesEndpointAndPairedCredentialsWithoutNetwork() {
        var config = new S3StorageConfig();
        for (String endpoint : new String[]{"ftp://example.test", "https://user:pass@example.test"})
            assertThatIllegalArgumentException().isThrownBy(() -> config.attachmentS3Client(URI.create(endpoint), "us-east-1", "", ""));
        for (String[] keys : new String[][]{{"access", ""}, {"", "secret"}})
            assertThatIllegalArgumentException().isThrownBy(() -> config.attachmentS3Client(URI.create("https://example.test"), "us-east-1", keys[0], keys[1]));
        try (var explicit = config.attachmentS3Client(URI.create("https://example.test"), "us-east-1", "test-access", "test-secret");
             var defaults = config.attachmentS3Client(URI.create("http://127.0.0.1:1"), "us-east-1", "", "")) {
            assertThat(explicit.serviceName()).isEqualTo("s3");
            assertThat(defaults.serviceName()).isEqualTo("s3");
            assertThat(config.attachmentObjectStore(explicit, "bucket", "test", false, 1024)).isInstanceOf(S3AttachmentStore.class);
        }
    }
}
