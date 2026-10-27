package com.personalos.chat.service;

/**
 * 摘要刷新时读取的会话快照。
 *
 * <p>刻意放在顶层而不是 {@code ChatSummaryService} 的嵌套 record（2026-10-27 修复）：
 * 嵌套 record 会给外层类设置 NestHost 属性，JDK 21 的 Instrumentation 拒绝
 * ByteBuddy（Mockito inline mock maker）在 retransform 时修改 NestHost/NestMembers，
 * 于是 mock 外层类会抛 {@code UnsupportedOperationException: class redefinition failed}。
 * 移到顶层后不再有 Nest 关系，mock 恢复正常。
 *
 * @param conversationId  会话标识
 * @param boundary        本次要覆盖到的边界（窗口起点 - 1）
 * @param previousUptoSeq 读取时的已覆盖边界，写入前用它做乐观校验
 * @param existingSummary 已有摘要（可能为空）
 * @param transcript      已拼好的「角色：内容」纯文本
 */
record SummarySnapshot(String conversationId, int boundary, int previousUptoSeq,
                       String existingSummary, String transcript) {
}
