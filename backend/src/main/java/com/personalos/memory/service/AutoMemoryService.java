package com.personalos.memory.service;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.ai.ContextProperties;
import com.personalos.common.ai.PostTurnContributor;
import com.personalos.common.json.JsonCodec;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 记忆自动沉淀（阶段 22）：轮次结束后让模型从对话里抽取值得长期记住的陈述。
 *
 * <h2>默认关闭，且必须如此</h2>
 * 这是整套上下文机制里**唯一有副作用**的部分：写错的摘要只是少点信息，
 * 写错的记忆会变成「永久约束」，之后每一轮对话都会照它执行。
 * 模型把一次性偏好（「今天先按 A 方案」）固化成长期规则，是很难察觉、也很难撤回的脏数据。
 *
 * <p>因此：总开关默认 false、每轮上限 3 条、长度有上下限、
 * 写入走 {@link MemoryService#create} 的内容幂等（重复内容不会二次落库）。
 *
 * <h2>为什么和摘要共用一个线程池</h2>
 * 两者都是「本轮结束后、只影响下一轮」的模型调用，且都允许丢弃。
 * 合成一个**有小界队列**的池，可以在模型变慢时整体退让，而不是各占一半线程。
 */
@Service
public class AutoMemoryService implements PostTurnContributor {

    private static final Logger log = LoggerFactory.getLogger(AutoMemoryService.class);

    private static final String CAPTURE_SYSTEM = """
            你负责判断这段对话里有哪些内容值得**长期**记住。
            只保留：用户明确表达的长期偏好、身份或环境事实、反复出现的约束、约定好的规则。
            不要保留：一次性的任务内容、临时的方案、对话过程、任何需要推测的内容。
            只输出 JSON 字符串数组，不含任何解释；没有值得记住的内容时输出 []。
            """;

    private final MemoryService memoryService;
    private final AiChatClientProvider aiProvider;
    private final ContextProperties properties;
    private final JsonCodec jsonCodec;
    private final ExecutorService executor;

    public AutoMemoryService(MemoryService memoryService,
                             AiChatClientProvider aiProvider,
                             ContextProperties properties,
                             JsonCodec jsonCodec,
                             @Qualifier("contextTaskExecutor") ExecutorService executor) {
        this.memoryService = memoryService;
        this.aiProvider = aiProvider;
        this.properties = properties;
        this.jsonCodec = jsonCodec;
        this.executor = executor;
    }

    /**
     * {@link PostTurnContributor} 入口：对话模块不认「记忆」这个概念，只按 SPI 回调。
     */
    @Override
    public void afterTurn(String conversationId, String userMessage, String assistantReply) {
        captureAsync(userMessage, assistantReply);
    }

    /**
     * 提交一次沉淀（异步、尽力而为）。
     *
     * @param userMessage      本轮用户消息
     * @param assistantReply   本轮助手回复（空回复不处理）
     */
    public void captureAsync(String userMessage, String assistantReply) {
        if (!properties.autoMemory().enabled()) {
            return;
        }
        if (userMessage == null || userMessage.isBlank()
                || assistantReply == null || assistantReply.isBlank()) {
            return;
        }
        try {
            executor.execute(() -> capture(userMessage, assistantReply));
        } catch (RejectedExecutionException ex) {
            log.debug("记忆沉淀任务被丢弃（队列已满）");
        }
    }

    /** 同步执行一次沉淀；可见于测试 */
    public void capture(String userMessage, String assistantReply) {
        List<String> facts;
        try {
            String raw = aiProvider.requirePlainChatClient()
                    .prompt()
                    .system(CAPTURE_SYSTEM)
                    .user("用户：" + userMessage + "\n助手：" + assistantReply)
                    .call()
                    .content();
            facts = MemoryFactParser.parse(jsonCodec, raw, properties.autoMemory().maxItems());
        } catch (RuntimeException ex) {
            // 沉淀失败对用户完全不可见：它是后台增强，不是对话的一部分
            log.debug("记忆自动沉淀失败", ex);
            return;
        }
        for (String fact : facts) {
            try {
                // 走 createAuto：来源标记为 AUTO，页面上能与手工记忆区分开
                memoryService.createAuto(fact);
            } catch (RuntimeException ex) {
                // 单条失败不影响其余条目（多半是长度或内容校验没过）
                log.debug("单条记忆沉淀被拒绝 fact={}", fact, ex);
            }
        }
        if (!facts.isEmpty()) {
            log.info("自动沉淀记忆 {} 条", facts.size());
        }
    }
}
