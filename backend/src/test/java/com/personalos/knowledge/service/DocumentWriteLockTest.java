package com.personalos.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文档写互斥的语义。
 *
 * <p>这层逻辑看不出对错：写在 Service 私有字段里时，只能靠「完整替换两次文档」
 * 间接验证，而那条路径要跑几十秒且不稳定。抽成独立类之后三条语义可直接断言。
 */
class DocumentWriteLockTest {

    private final DocumentWriteLock lock = new DocumentWriteLock();

    @Test
    @DisplayName("同一文档二次抢占：抛 409，而不是等待")
    void rejectsSecondAcquire() {
        UUID id = UUID.randomUUID();

        lock.acquire(id);

        assertThatThrownBy(() -> lock.acquire(id))
                .isInstanceOf(BusinessException.class)
                // BUSINESS_CONFLICT → HTTP 409。用 500 会让前端误判成服务端故障
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_CONFLICT);
    }

    @Test
    @DisplayName("释放后可再次抢占：否则文档被永久锁死")
    void canReacquireAfterRelease() {
        UUID id = UUID.randomUUID();

        lock.acquire(id);
        lock.release(id);

        assertThat(lock.isHeld(id)).isFalse();
        assertThatCode(() -> lock.acquire(id)).doesNotThrowAnyException();
        assertThat(lock.isHeld(id)).isTrue();
    }

    @Test
    @DisplayName("不同文档互不影响：锁的粒度是单篇文档，不是全局串行")
    void differentDocumentsDoNotBlockEachOther() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        lock.acquire(first);

        assertThatCode(() -> lock.acquire(second)).doesNotThrowAnyException();
        assertThat(lock.isHeld(first)).isTrue();
        assertThat(lock.isHeld(second)).isTrue();
    }

    @Test
    @DisplayName("重复释放是幂等的：多条终止路径可能都调 release")
    void releaseIsIdempotent() {
        UUID id = UUID.randomUUID();
        lock.acquire(id);

        assertThatCode(() -> {
            lock.release(id);
            lock.release(id);
        }).doesNotThrowAnyException();
    }
}
