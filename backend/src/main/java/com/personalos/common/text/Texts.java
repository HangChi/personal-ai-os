package com.personalos.common.text;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;

/**
 * 文本归一与校验的公共辅助。
 *
 * <h2>为什么集中在这里</h2>
 * 「去首尾空白、空白串归一为 null」与「必填文本 + 长度上限」这两条规则，
 * 在任务与日程两条写路径上**完全相同**，却各自抄了一份私有方法。
 * 两处各写一份的代价不是重复的代码行数，而是**规则会分叉** ——
 * 改了一处的长度上限而忘了另一处时，没有任何编译错误会提醒你。
 *
 * <p>因此这里只放「多模块共有且会同时变动」的规则；单模块特有的校验仍留在各自服务里。
 */
public final class Texts {

    /**
     * 「标题类」文本的统一长度上限。
     *
     * <p>任务标题与日程标题共用同一个上限：对用户而言它们都是「一行标题」，
     * 没有理由一个允许 200 字、另一个允许 500 字。
     */
    public static final int TITLE_MAX_LENGTH = 200;

    private Texts() {
    }

    /**
     * 去除首尾空白；{@code null} 或全空白返回 {@code null}。
     *
     * <p>返回 {@code null} 而不是空串，是为了让「未填写」与「填了空白」在数据库里是同一种状态 ——
     * 否则会出现「有描述」但描述是空串的记录，前端还要再判断一次。
     */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * 校验必填文本，返回去空白后的结果。
     *
     * @param value     原始文本
     * @param label     字段名，用于错误提示（如「标题」）
     * @param maxLength 长度上限，按**去空白后**的字符数计算
     * @throws BusinessException 为空或超出长度上限时（{@code VALIDATION_FAILED}）
     */
    public static String requireNotBlank(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    label + "长度不能超过 " + maxLength);
        }
        return stripped;
    }
}
