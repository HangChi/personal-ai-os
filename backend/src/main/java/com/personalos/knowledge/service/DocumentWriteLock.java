package com.personalos.knowledge.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 知识文档「替换 / 删除」的进程内互斥。
 *
 * <h2>为什么需要它</h2>
 * 替换文档的流程是「删旧向量 → <b>事务外</b>向量化 → 写新记录」，三小段事务中间夹着
 * 数十秒的嵌入 HTTP 调用。两个并发 PUT 会各删一次旧向量、各写一代新向量，
 * 两代混在同一个 {@code document_id} 下 —— 检索会命中**文档里已经不存在的段落**：
 * 有出处、点开对不上，而且没有任何入口能修复。
 *
 * <h2>为什么不用数据库行锁</h2>
 * 行锁只能在单个事务内持有，短事务一提交就释放。要覆盖整个窗口就得把嵌入调用塞回
 * 事务里，而那正是 2026-10-27 花大力气移出去的东西（连接池只有 10 条、超时 5 秒）。
 * 所以这里与 {@code ChatCompletionService.activeStreams} 同一手法：按 id 原子抢占。
 *
 * <p><b>适用边界</b>：单用户自托管、单实例。多实例部署时这层会失效，
 * 需要换成带心跳与过期的分布式锁（或文档状态机 + 抢占），
 * 不能只补一个数据库行锁 —— 那解决不了「跨事务外调用」这个根本矛盾。
 *
 * <h2>为什么单独成类</h2>
 * 它是本次修复里唯一「逻辑正确性无法靠肉眼确认」的部分：写在 Service 私有字段里，
 * 测试只能通过完整走一遍上传/替换来间接验证，而那条路径要几十秒且不稳定。
 * 抽出来之后，抢占/释放/重复抢占三条语义可以直接断言。
 */
@Component
public class DocumentWriteLock {

    private static final Logger log = LoggerFactory.getLogger(DocumentWriteLock.class);

    /** 值为占位；只借 {@link ConcurrentHashMap#putIfAbsent} 的原子性 */
    private final ConcurrentHashMap<UUID, Boolean> holders = new ConcurrentHashMap<>();

    /**
     * 抢占某文档的独占权；已被占用时**立即失败**而不是排队。
     *
     * <p>不排队是有意的：一次替换要几十秒，排队会让用户以为第二个请求卡死了。
     * 明确回「正在更新中」比默默等待更有用，也不会让请求线程被长时间占住。
     *
     * @throws BusinessException 已被占用（40900 / HTTP 409）
     */
    public void acquire(UUID documentId) {
        if (holders.putIfAbsent(documentId, Boolean.TRUE) != null) {
            log.debug("文档 {} 正在被替换或删除，拒绝并发写", documentId);
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "该文档正在更新或删除中，请稍后再试");
        }
    }

    /**
     * 释放独占权。
     *
     * <p>必须放在 {@code finally} 里：任何异常路径漏掉都会把文档永久锁死，
     * 且症状是「这个文档再也改不了」，重启才恢复。
     */
    public void release(UUID documentId) {
        holders.remove(documentId);
    }

    /** 仅供测试与排障：当前是否有写操作在进行 */
    public boolean isHeld(UUID documentId) {
        return holders.containsKey(documentId);
    }
}
