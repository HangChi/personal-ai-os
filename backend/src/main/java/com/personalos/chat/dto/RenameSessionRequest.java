package com.personalos.chat.dto;

/**
 * 会话重命名请求。
 *
 * <h2>为什么这里没有 {@code @Size}</h2>
 * Bean Validation 的长度约束校验的是**原始**字符串长度，而业务要求按**去除首尾空白后**
 * 的长度判定 —— 两者在「带缩进的合法标题」上会给出相反结论
 * （例：首尾各 20 个空格 + 190 个字符，原始 230 但归一后 190）。
 * 若两处并存，用户会看到「看起来没超 200 却被拒」，且没有任何提示能解释原因。
 *
 * <p>因此校验只在 {@code ChatSessionService#rename} 一处，
 * 经 {@code Texts.requireNotBlank} 完成（空值与长度同源，上限为 {@code Texts.TITLE_MAX_LENGTH}）。
 * 决策与被否决方案见 {@code specs/007-chat-session-rename/research.md} R-02。
 *
 * @param title 新标题；缺失或全空白会被拒绝（不是「保持不变」）
 */
public record RenameSessionRequest(String title) {
}
