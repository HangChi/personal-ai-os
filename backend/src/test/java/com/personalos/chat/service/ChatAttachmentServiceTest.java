package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ChatAttachmentService} 单元测试。
 *
 * <p>重点是**安全边界**而不是happy path：标识来自客户端，
 * 一旦被当成路径用，{@code ../} 就能写到磁盘上任意位置。
 */
class ChatAttachmentServiceTest {

    @TempDir
    Path tempDir;

    private ChatAttachmentService service;

    /** 与 application.yml 的默认值一致（6MB）；上限本身有独立用例 */
    private static final long DEFAULT_MAX_BYTES = 6L * 1024 * 1024;

    @BeforeEach
    void setUp() {
        service = newService(DEFAULT_MAX_BYTES);
    }

    private ChatAttachmentService newService(long maxAttachmentBytes) {
        return new ChatAttachmentService(new JsonCodec(JsonMapper.builder().build()),
                tempDir.toString(), maxAttachmentBytes);
    }

    @Test
    @DisplayName("store(): 上限是 KB 级时，提示也按 KB 说，不会写成「0MB」")
    void describesKilobyteLimit() {
        ChatAttachmentService small = newService(1024);

        assertThatThrownBy(() -> small.store(new MockMultipartFile("file", "a.txt", "text/plain",
                "x".repeat(2000).getBytes())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1KB");
    }

    @Test
    @DisplayName("store(): 默认 6MB 上限的提示是 6MB（与 application.yml 一致）")
    void describesMegabyteLimit() {
        assertThatThrownBy(() -> service.store(new MockMultipartFile("file", "huge.bin",
                "application/octet-stream", new byte[(int) DEFAULT_MAX_BYTES + 1])))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("6MB");
    }

    @Test
    @DisplayName("store(): 超长 / 带换行的文件名不影响落盘（日志里的名字会被清洗限长）")
    void acceptsHostileFilename() {
        // 文件名来自客户端：含 \n 可伪造日志整行，超长会把一行日志撑爆。
        // 落盘用 UUID，所以这里只验证「能存下来且元数据保留原名」。
        String hostile = "a\nb".repeat(200) + ".txt";

        ChatAttachmentDto dto = service.store(new MockMultipartFile("file", hostile, "text/plain",
                "内容".getBytes()));

        assertThat(dto.filename()).isEqualTo(hostile);
        assertThat(Files.exists(tempDir.resolve(dto.id()))).isTrue();
    }

    @Test
    @DisplayName("store(): 超过配置的上限即拒绝，提示里的数字来自配置而不是写死的 20MB")
    void rejectsFileLargerThanConfiguredLimit() {
        // 上限 8 字节：让「应用层先于容器拦下」这件事在单元测试里可验证。
        // 曾经应用层写死 20MB、容器只放 6MB，7~20MB 的文件在进控制器前就被拒，
        // 用户按提示压缩到 10MB 仍然失败 —— 因为真实门槛是 6MB。
        ChatAttachmentService small = newService(8);

        MockMultipartFile file = new MockMultipartFile("file", "big.txt",
                "text/plain", "0123456789".getBytes());

        assertThatThrownBy(() -> small.store(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("8 字节");
    }

    @Test
    @DisplayName("store(): 落盘并返回名片，文件名不当路径用")
    void storesFileAndReturnsCard() {
        MockMultipartFile file = new MockMultipartFile("file", "周报.txt",
                "text/plain", "内容".getBytes());

        ChatAttachmentDto dto = service.store(file);

        assertThat(dto.filename()).isEqualTo("周报.txt");
        assertThat(dto.size()).isEqualTo("内容".getBytes().length);
        // 文件名不进路径：磁盘上的名字是 UUID
        assertThat(Files.exists(tempDir.resolve(dto.id()))).isTrue();
        assertThat(Files.exists(tempDir.resolve("周报.txt"))).isFalse();
    }

    @Test
    @DisplayName("load(): 只接受 UUID 形态的标识，穿越尝试一律按不存在处理")
    void rejectsNonUuidIdentifiers() {
        assertThatThrownBy(() -> service.load("../../etc/passwd"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.load("abc"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.load(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("load(): 标识合法但文件不存在时按不存在处理（不抛 IO 细节）")
    void missingFileIsNotFound() {
        assertThatThrownBy(() -> service.load("00000000-0000-4000-8000-000000000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件不存在");
    }

    @Test
    @DisplayName("verify(): 用磁盘真实大小覆盖客户端声明值")
    void verifyOverridesDeclaredSize() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt",
                "text/plain", "12345".getBytes());
        ChatAttachmentDto stored = service.store(file);
        // 客户端谎报一个更大的 size
        ChatAttachmentDto declared = new ChatAttachmentDto(stored.id(), "a.txt", "text/plain", 999_999);

        assertThat(service.verify(java.util.List.of(declared)))
                .containsExactly(new ChatAttachmentDto(stored.id(), "a.txt", "text/plain", 5));
    }

    @Test
    @DisplayName("verify(): 未上传过的标识被忽略，而不是照单全收")
    void verifyDropsUnknownIdentifiers() {
        ChatAttachmentDto forged = new ChatAttachmentDto(
                "00000000-0000-4000-8000-000000000000", "x.txt", "text/plain", 1);

        assertThat(service.verify(java.util.List.of(forged))).isEmpty();
        assertThat(service.verify(null)).isEmpty();
    }

    @Test
    @DisplayName("JSON 往返：空列表存 null，坏数据按无附件处理")
    void jsonRoundTrip() {
        assertThat(service.toJson(java.util.List.of())).isNull();
        assertThat(service.fromJson(null)).isEmpty();
        assertThat(service.fromJson("这不是 JSON")).isEmpty();

        ChatAttachmentDto dto = new ChatAttachmentDto("id", "f.txt", "text/plain", 3);
        assertThat(service.fromJson(service.toJson(java.util.List.of(dto)))).containsExactly(dto);
    }
}
