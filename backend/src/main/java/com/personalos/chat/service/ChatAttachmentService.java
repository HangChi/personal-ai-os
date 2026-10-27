package com.personalos.chat.service;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 聊天附件的存取（阶段 22）。
 *
 * <h2>存哪、怎么存</h2>
 * 文件本体落盘到 {@code app.chat.upload-dir}（默认 {@code backend/data/uploads}），
 * 文件名用 UUID —— 原始文件名只作为元数据保存，不参与拼路径。
 * 后者是被路径穿越的经典入口：{@code ../../etc/passwd} 之类的名字一旦拼进路径，
 * 就能写到磁盘上任意位置。
 *
 * <h2>为什么不用数据库存内容</h2>
 * 消息查询是对话页的高频路径，把大字段塞进 {@code chat_message} 会拖慢每一次拉取；
 * 而附件只需要一个定位符。库里只存元数据快照（见 V14）。
 */
@Service
public class ChatAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

    /** 单文件大小上限：20MB。再大就更像「文件管理」而不是聊天附件 */
    private static final long MAX_SIZE = 20L * 1024 * 1024;

    private final Path uploadDir;
    private final JsonCodec jsonCodec;

    public ChatAttachmentService(JsonCodec jsonCodec,
                                 @Value("${app.chat.upload-dir:data/uploads}") String uploadDir) {
        this.jsonCodec = jsonCodec;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * 保存上传文件，返回元数据。
     *
     * @param file 上传的文件的
     * @return 附件名片
     */
    public ChatAttachmentDto store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(com.personalos.common.api.ErrorCode.VALIDATION_FAILED, "文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(com.personalos.common.api.ErrorCode.VALIDATION_FAILED,
                    "文件不能超过 20MB");
        }
        String id = UUID.randomUUID().toString();
        String filename = file.getOriginalFilename() == null ? id : file.getOriginalFilename();
        try {
            Files.createDirectories(uploadDir);
            Path target = uploadDir.resolve(id);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("附件写入失败", ex);
        }
        String contentType = file.getContentType() == null
                ? "application/octet-stream"
                : file.getContentType();
        log.info("附件已保存 id={} 文件名={} 大小={}", id, filename, file.getSize());
        return new ChatAttachmentDto(id, filename, contentType, file.getSize());
    }

    /**
     * 按标识取文件内容。
     *
     * <p>标识同样不当路径用：只接受「UUID 形态」的字符串，
     * 从根上排除 {@code ../} 之类的穿越尝试（纵深防御，不依赖调用方先校验）。
     */
    public Resource load(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
            throw new BusinessException(com.personalos.common.api.ErrorCode.RESOURCE_NOT_FOUND, "附件不存在");
        }
        Path file = uploadDir.resolve(id).normalize();
        if (!file.startsWith(uploadDir) || !Files.isRegularFile(file)) {
            throw new BusinessException(com.personalos.common.api.ErrorCode.RESOURCE_NOT_FOUND, "附件不存在");
        }
        try {
            return new UrlResource(file.toUri());
        } catch (IOException ex) {
            throw new BusinessException(com.personalos.common.api.ErrorCode.RESOURCE_NOT_FOUND, "附件不存在");
        }
    }

    /**
     * 校验上传结果并以**磁盘上的真实大小**重建名片。
     *
     * <p>为什么不能信请求里的 size：客户端传来的只是展示元数据，
     * 被改写也不会报错，但界面显示的大小就成了假数字。这里逐个确认文件真实存在，
     * 并用 {@link Files#size} 覆盖 —— 顺带把「伪造一个不存在的附件标识」挡住。
     *
     * @param declared 客户端声明的名片
     * @return 校验通过的名片；空列表原样返回
     */
    public List<ChatAttachmentDto> verify(List<ChatAttachmentDto> declared) {
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        List<ChatAttachmentDto> verified = new ArrayList<>();
        for (ChatAttachmentDto dto : declared) {
            if (dto == null || dto.id() == null || !dto.id().matches("[0-9a-fA-F-]{36}")) {
                continue;
            }
            Path file = uploadDir.resolve(dto.id()).normalize();
            if (!file.startsWith(uploadDir) || !Files.isRegularFile(file)) {
                log.warn("附件标识未对应到已上传文件，已忽略 id={}", dto.id());
                continue;
            }
            try {
                verified.add(new ChatAttachmentDto(dto.id(), dto.filename(), dto.contentType(), Files.size(file)));
            } catch (IOException ex) {
                log.warn("读取附件大小失败，已忽略 id={}", dto.id());
            }
        }
        return verified;
    }

    /** 序列化为库里的 JSON；空列表返回 null（不写空数组，省得每条消息都带六个字符） */
    public String toJson(List<ChatAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        return jsonCodec.toJson(attachments);
    }

    /**
     * 读取附件的原始字节。
     *
     * <p>与 {@link #load} 的区别：那个返回 {@code Resource} 用于直接作为 HTTP 响应体流出去，
     * 这个把内容读进内存，供「送进模型」与「文本抽取」使用。
     * 调用方必须已经知道文件不大（上传期已限 20MB），否则不该用这个。
     */
    public byte[] readBytes(String id) {
        Resource resource = load(id);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new BusinessException(com.personalos.common.api.ErrorCode.RESOURCE_NOT_FOUND, "附件读取失败");
        }
    }

    /**
     * 删除一个附件的磁盘文件。
     *
     * <p>只删文件、不碰数据库：调用方（删会话 / 删消息）已经在自己的事务里处理了记录。
     * 文件不存在视为成功 —— 清理是幂等的，不该因为「已经没了」而让删除会话失败。
     */
    public void delete(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
            return;
        }
        Path file = uploadDir.resolve(id).normalize();
        if (!file.startsWith(uploadDir)) {
            return;
        }
        try {
            if (Files.deleteIfExists(file)) {
                log.debug("已删除附件文件 id={}", id);
            }
        } catch (IOException ex) {
            // 清理失败只记日志：数据库记录已经删了，为它回滚整次删除不合理；
            // 残留文件由后续的孤儿巡检兜底（见 ChatAttachmentJanitor）
            log.warn("附件文件删除失败，将残留 id={}", id, ex);
        }
    }

    /** 批量删除附件文件（忽略空值与重复） */
    public void deleteAll(List<ChatAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        attachments.forEach(attachment -> {
            if (attachment != null) {
                delete(attachment.id());
            }
        });
    }

    /** 附件目录（供孤儿巡检使用） */
    public Path uploadDirectory() {
        return uploadDir;
    }

    /**
     * 反序列化附件快照。
     *
     * <p>解析失败返回空列表而不是抛异常：附件是增强信息，
     * 一条坏数据不该让整页消息加载失败（与引用快照同策）。
     */
    public List<ChatAttachmentDto> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            ChatAttachmentDto[] parsed = jsonCodec.fromJson(json, ChatAttachmentDto[].class);
            return parsed == null ? List.of() : List.of(parsed);
        } catch (RuntimeException ex) {
            log.warn("附件快照解析失败，按无附件处理：{}", ex.getMessage());
            return List.of();
        }
    }
}
