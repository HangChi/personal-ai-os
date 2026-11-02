package com.personalos.knowledge.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 跨进程文档独占租约；网络调用不占连接，最终写事务必须调用 fence。 */
@Component
public class DocumentWriteLock {

    private static final Logger log = LoggerFactory.getLogger(DocumentWriteLock.class);

    /** 值为占位；只借 {@link ConcurrentHashMap#putIfAbsent} 的原子性 */
    private final ConcurrentHashMap<UUID, Boolean> holders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, com.personalos.common.web.DatabaseCoordination.Lease> shared = new ConcurrentHashMap<>();
    private com.personalos.common.web.DatabaseCoordination coordination;

    @org.springframework.beans.factory.annotation.Autowired
    void coordinate(com.personalos.common.web.DatabaseCoordination coordination) { this.coordination = coordination; }

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
        if (coordination != null) {
            try {
                var lease = coordination.acquire("document:" + documentId, 1);
                if (lease == null) throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "该文档正在其他进程更新，请稍后重试");
                shared.put(documentId, lease);
            } catch (RuntimeException ex) { holders.remove(documentId); throw ex; }
        }
    }

    /** 最终写事务必须确认租约归属，阻止失去租约的旧 worker 写入。 */
    public void fence(UUID documentId) {
        if (!holders.containsKey(documentId)) throw new IllegalStateException("Document lock is not held");
        var lease = shared.get(documentId);
        if (lease != null) lease.fence();
    }

    /**
     * 释放独占权。
     *
     * <p>必须放在 {@code finally} 里：任何异常路径漏掉都会把文档永久锁死，
     * 且症状是「这个文档再也改不了」，重启才恢复。
     */
    public void release(UUID documentId) {
        var lease = shared.remove(documentId);
        try { if (lease != null) lease.close(); }
        finally { holders.remove(documentId); }
    }

    /** 仅供测试与排障：当前是否有写操作在进行 */
    public boolean isHeld(UUID documentId) {
        return holders.containsKey(documentId);
    }
}
