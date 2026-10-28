package com.personalos.memory;

import com.personalos.memory.service.MemoryEmbeddingService;
import com.personalos.memory.service.MemoryEmbeddingStore;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 存量记忆的向量回填（V26）。
 *
 * <h2>为什么必须有它</h2>
 * V26 之前写入的记忆没有向量。不回填的话，那批「最老的、往往也是最硬的约束」
 * 会永久排在相关度 0 的位置 —— 排序功能上线即对历史数据失效，
 * 而且没有任何报错，表现是「新记的东西总是排在前面」。
 *
 * <h2>三个自我约束</h2>
 * <ol>
 *   <li><b>后台虚拟线程</b>：不阻塞启动，也不与对话抢平台线程；</li>
 *   <li><b>小批量</b>（默认 20）：回填不该把嵌入端点的配额一次性吃满；</li>
 *   <li><b>连续失败即停</b>：嵌入服务挂了就安静退出，等下个进程再试 ——
 *       死循环重试只会把日志刷满，不会让向量自己出现。</li>
 * </ol>
 *
 * <p>「本轮零进展就退出」是防死循环的关键：某些条目可能因为维度不符永远写不进
 * {@code embedded_at}，那样 {@code findPending} 会一遍遍返回同一批。
 */
@Component
public class MemoryEmbeddingBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingBackfill.class);

    /** 连续失败多少次就放弃本轮 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final MemoryProperties properties;
    private final MemoryEmbeddingService embeddingService;
    private final MemoryEmbeddingStore embeddingStore;

    public MemoryEmbeddingBackfill(MemoryProperties properties,
                                   MemoryEmbeddingService embeddingService,
                                   MemoryEmbeddingStore embeddingStore) {
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.embeddingStore = embeddingStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.backfill().enabled()) {
            return;
        }
        if (!embeddingService.isAvailable()) {
            log.debug("没有可用的嵌入模型，跳过记忆向量回填");
            return;
        }
        Thread.ofVirtual().name("memory-embed-backfill").start(this::backfill);
    }

    /**
     * 回填主循环。公开是为了让测试能同步驱动它（不必等后台线程）。
     *
     * @return 成功写入向量的条数
     */
    public int backfill() {
        int succeeded = 0;
        while (true) {
            List<MemoryEmbeddingStore.PendingEmbedding> batch =
                    embeddingStore.findPending(properties.backfill().batchSize());
            if (batch.isEmpty()) {
                break;
            }
            int progressed = 0;
            int failures = 0;
            for (MemoryEmbeddingStore.PendingEmbedding pending : batch) {
                Optional<float[]> vector = embeddingService.embed(pending.content());
                if (vector.isEmpty()) {
                    failures++;
                } else if (embeddingStore.save(pending.id(), vector.get(), embeddingService.modelTag())) {
                    progressed++;
                    succeeded++;
                    failures = 0;
                } else {
                    failures++;
                }
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    log.info("记忆向量回填连续失败 {} 次，暂停本轮（已补 {} 条）", failures, succeeded);
                    return succeeded;
                }
            }
            if (progressed == 0) {
                // 一条都没写进去：继续循环只会拿到同一批，没有意义
                log.info("记忆向量回填本轮无进展，结束（已补 {} 条）", succeeded);
                return succeeded;
            }
        }
        if (succeeded > 0) {
            log.info("记忆向量回填完成，共补 {} 条", succeeded);
        }
        return succeeded;
    }
}
