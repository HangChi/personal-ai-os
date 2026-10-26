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

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentService(new JsonCodec(JsonMapper.builder().build()),
                tempDir.toString());
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
