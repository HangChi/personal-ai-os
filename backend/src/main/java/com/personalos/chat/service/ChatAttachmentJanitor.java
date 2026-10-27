package com.personalos.chat.service;

import com.personalos.chat.dto.ChatAttachmentDto;
import com.personalos.chat.repository.ChatMessageRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 孤儿附件巡检：删掉磁盘上「数据库里已经没有引用」的文件。
 *
 * <h2>为什么还需要它</h2>
 * 正常路径（删会话）已经在事务提交后清理文件，但仍有三种情况会漏：
 * <ul>
 *   <li>{@code afterCommit} 阶段的删除失败（磁盘只读、文件被占用）；</li>
 *   <li>本次改动之前产生的历史残留；</li>
 *   <li>上传成功但从未发送出去 —— 文件落盘了，却没有任何消息引用它。</li>
 * </ul>
 * 与其在每条路径上挂钩子（漏一条就是永久泄漏），不如像提醒模块那样做**自愈**：
 * 定期以数据库为准，把磁盘上对不上的清掉。
 *
 * <h2>两条安全约束</h2>
 * <ol>
 *   <li><b>只删 UUID 形态的文件名</b>。上传时文件名由服务端生成（UUID），
 *       用户原始文件名只作元数据 —— 万一有人往目录里放了别的东西，不会被误删。</li>
 *   <li><b>跳过最近 1 小时的文件</b>。上传与「消息落库」之间有时间窗
 *       （用户选了文件还得点发送），期间文件在磁盘上但库里没有引用。
 *       不加这条，巡检会把用户正在编辑的消息附件删掉。</li>
 * </ol>
 */
@Component
public class ChatAttachmentJanitor {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentJanitor.class);

    /** 上传到落库之间可能隔很久（用户写完消息才发），1 小时是保守下限 */
    private static final Duration GRACE = Duration.ofHours(1);

    private final ChatMessageRepository messageRepository;
    private final ChatAttachmentService attachmentService;
    private final boolean enabled;

    public ChatAttachmentJanitor(ChatMessageRepository messageRepository,
                                 ChatAttachmentService attachmentService,
                                 @Value("${app.chat.attachment-janitor-enabled:true}") boolean enabled) {
        this.messageRepository = messageRepository;
        this.attachmentService = attachmentService;
        this.enabled = enabled;
    }

    /**
     * 每天凌晨 4:30 巡检一次。
     *
     * <p>不与提醒扫描（每 60 秒）共用节奏：那是时效性任务，这是清理任务，
     * 一天一次足够，且不会和用户的使用高峰撞上。
     */
    @Scheduled(cron = "${app.chat.attachment-janitor-cron:0 30 4 * * *}")
    public void pruneOrphans() {
        if (!enabled) {
            return;
        }
        Path dir = attachmentService.uploadDirectory();
        if (!Files.isDirectory(dir)) {
            return;
        }

        Set<String> referenced = referencedIds();
        Instant threshold = Instant.now().minus(GRACE);
        int removed = 0;

        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String name = file.getFileName().toString();
                if (!name.matches("[0-9a-fA-F-]{36}") || referenced.contains(name)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isAfter(threshold)) {
                        continue;
                    }
                    if (Files.deleteIfExists(file)) {
                        removed++;
                    }
                } catch (IOException ex) {
                    log.warn("孤儿附件删除失败 file={}", name, ex);
                }
            }
        } catch (IOException ex) {
            log.warn("附件目录扫描失败 dir={}", dir, ex);
            return;
        }

        if (removed > 0) {
            log.info("孤儿附件清理完成，删除 {} 个文件（库中引用 {} 个）", removed, referenced.size());
        } else {
            log.debug("孤儿附件巡检完成，无残留");
        }
    }

    /** 库里所有被引用的附件 id */
    private Set<String> referencedIds() {
        List<String> jsonList = messageRepository.findAllAttachmentJson();
        Set<String> ids = new HashSet<>();
        for (String json : jsonList) {
            for (ChatAttachmentDto dto : attachmentService.fromJson(json)) {
                if (dto != null && dto.id() != null) {
                    ids.add(dto.id());
                }
            }
        }
        return ids;
    }
}
