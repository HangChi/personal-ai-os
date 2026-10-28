package com.personalos.common.ai.config.dto;

import java.util.List;

/**
 * 模型列表探测结果。
 *
 * <h2>为什么失败也返回 200 + {@code ok=false}</h2>
 * 「这个端点不提供模型列表」是**正常情况**而不是错误：大量自建与中转端点
 * 根本没实现 {@code /models}，但它们能正常聊天。若用 HTTP 错误表达，
 * 前端与用户都会把它当成「配置写错了」，而正确的应对是「照常手打模型名」。
 * 口径与 {@link ModelConnectionTestResult} 保持一致。
 *
 * <h2>为什么还要区分 {@code kind}</h2>
 * 同样是 {@code ok=false}，「该端点不提供列表」与「密钥错了 / 网络不通」
 * 对用户的意义完全相反：前者不用管，后者必须处理。仅靠文案在前端做关键字匹配
 * 太脆（改一句话就失效），所以由服务端直接给出语义分类，前端据此选提示样式。
 *
 * @param ok        是否成功拿到模型列表
 * @param models    模型名（已去重、按字母序排好）；失败时为空列表
 * @param latencyMs 往返耗时（毫秒）
 * @param error     失败原因（面向用户的可读文案）；成功时为 null
 * @param kind      失败分类；成功时为 null
 */
public record ModelListResult(
        boolean ok, List<String> models, long latencyMs, String error, ProbeFailureKind kind) {

    /** 紧凑构造器：防空列表进 null，前端无需判两种「空」 */
    public ModelListResult {
        models = models == null ? List.of() : List.copyOf(models);
    }

    /**
     * 兼容构造器：不指定分类时按「真的出错」处理。
     *
     * <p>刻意保留 4 参形态 —— 成功结果与测试里的调用点大多不关心分类，
     * 加一个必填参数会让所有调用点都得写一个无意义的 {@code null}。
     */
    public ModelListResult(boolean ok, List<String> models, long latencyMs, String error) {
        this(ok, models, latencyMs, error, ok ? null : ProbeFailureKind.ERROR);
    }

    /** 端点不提供模型列表：属于正常情况，无需用户处理 */
    public static ModelListResult unsupported(String error) {
        return new ModelListResult(false, List.of(), 0, error, ProbeFailureKind.UNSUPPORTED);
    }

    /** 需要用户处理：认证失败、网络不通、前置条件缺失等 */
    public static ModelListResult failed(String error) {
        return new ModelListResult(false, List.of(), 0, error, ProbeFailureKind.ERROR);
    }

    /**
     * 失败分类：决定前端用什么语气提示。
     *
     * <ul>
     *   <li>{@link #UNSUPPORTED} —— 端点没实现模型列表接口。用中性提示，
     *       并明确告诉用户「不影响使用，手填模型名即可」。</li>
     *   <li>{@link #ERROR} —— 真的有东西不对（密钥、地址、网络），用警示色。</li>
     * </ul>
     */
    public enum ProbeFailureKind {
        UNSUPPORTED,
        ERROR,
    }
}
