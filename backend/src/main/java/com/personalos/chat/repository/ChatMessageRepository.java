package com.personalos.chat.repository;

import com.personalos.chat.entity.ChatMessageEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 消息仓储。
 *
 * <p>所有排序一律基于 {@code sequenceNo}，不依赖 {@code createdAt}（FR-018）。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /**
     * 按会话查询全部消息，正序。
     */
    List<ChatMessageEntity> findByConversationIdOrderBySequenceNoAsc(String conversationId);

    /**
     * @return 该会话当前的最大顺序号；无消息时为 0
     */
    @Query("select coalesce(max(m.sequenceNo), 0) from ChatMessageEntity m where m.conversationId = :conversationId")
    int findMaxSequenceNo(@Param("conversationId") String conversationId);

    /**
     * 取该会话**最近**的若干条消息（倒序取出，调用方负责反转成正序）。
     *
     * <p>用倒序 + 分页实现「取最后 N 条」，避免把整段历史读进内存。
     */
    @Query("select m from ChatMessageEntity m where m.conversationId = :conversationId order by m.sequenceNo desc")
    List<ChatMessageEntity> findRecentByConversationId(@Param("conversationId") String conversationId, Pageable pageable);

    /**
     * 取该会话中**早于** {@code beforeSeq} 的若干条消息（倒序取出，调用方负责反转成正序）。
     *
     * <p>用于「加载更早消息」：以当前列表最旧一条的顺序号为锚点向前翻页。
     * 用顺序号而不是 offset 做游标——翻页期间新消息只会追加在尾部，
     * 顺序号游标不会因插入而错位（offset 会）。
     */
    @Query("select m from ChatMessageEntity m where m.conversationId = :conversationId "
            + "and m.sequenceNo < :beforeSeq order by m.sequenceNo desc")
    List<ChatMessageEntity> findBeforeSequenceNo(@Param("conversationId") String conversationId,
                                                 @Param("beforeSeq") int beforeSeq,
                                                 Pageable pageable);

    long countByConversationId(String conversationId);

    /**
     * 取顺序号落在 {@code [fromSeq, toSeq]} 区间内的消息，正序。
     *
     * <p>供滚动摘要使用：摘要要消费的正是「已滑出上下文窗口、且尚未被摘要覆盖」那一段。
     * 上界用闭区间而不是 {@code <}，因为边界消息本身也需要进摘要 ——
     * 它已经不在窗口里了。
     */
    @Query("select m from ChatMessageEntity m where m.conversationId = :conversationId "
            + "and m.sequenceNo between :fromSeq and :toSeq order by m.sequenceNo asc")
    List<ChatMessageEntity> findRangeBySequenceNo(@Param("conversationId") String conversationId,
                                                   @Param("fromSeq") int fromSeq,
                                                   @Param("toSeq") int toSeq);

    /**
     * 一次性统计多个会话的消息数，避免在会话列表里逐条查询造成 N+1。
     *
     * @return 每项为 {@code [conversationId, count]}
     */
    @Query("select m.conversationId, count(m) from ChatMessageEntity m "
            + "where m.conversationId in :conversationIds group by m.conversationId")
    List<Object[]> countGroupedByConversationId(@Param("conversationIds") Collection<String> conversationIds);

    void deleteByConversationId(String conversationId);

    /**
     * 消息**内容**命中关键词的会话 id（去重），供全局搜索使用（阶段 17）。
     *
     * <p>命中正文时返回的是**会话**而不是单条消息：消息量级比元数据大 1~2 个数量级，
     * 直接作为结果会让一次搜索返回几百条，分组就失去意义。
     *
     * <p>用原生 SQL 而不是 JPQL：JPQL 的 {@code escape} 子句在各实现上的支持不一致，
     * 而这里**必须**转义 —— 否则用户搜 {@code 100%} 时 {@code %} 被当通配符，
     * 会命中「100 之后任意字符」，是「搜得越准、错得越巧」那一类问题。
     *
     * <p>参数必须由调用方传 {@code SearchPatterns.likePattern(keyword)}
     * （已含 {@code %} 包裹与转义），不要再自行拼接。
     *
     * <p>不做 order by：调用方会把「标题命中」的会话排在前面，
     * 这批只是补充，顺序不影响阅读。
     */
    @Query(value = "SELECT DISTINCT conversation_id FROM chat_message "
            + "WHERE lower(content) LIKE lower(?1) ESCAPE '\\'",
            nativeQuery = true)
    List<String> findConversationIdsByContent(String pattern, Pageable pageable);
}
