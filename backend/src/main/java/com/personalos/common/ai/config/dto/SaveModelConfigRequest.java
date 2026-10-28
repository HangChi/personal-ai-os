package com.personalos.common.ai.config.dto;

/**
 * 保存模型配置的请求体。
 *
 * <h2>{@code apiKey} 的三态约定（关键）</h2>
 * <ul>
 *   <li>{@code null} 或字段缺失 —— <b>不改动</b>现有密钥。前端不回填明文，
 *       用户只改模型名时提交的表单里没有密钥，这一态保证不会把密钥清空；</li>
 *   <li>非空字符串 —— 用新密钥覆盖；</li>
 *   <li>空字符串 —— 显式清空，回落到环境变量里那把。</li>
 * </ul>
 * 把「没传」与「传了空」区别对待，是这个 DTO 存在的唯一理由。
 * 如果只用一个字段、空即清空，那么「改模型名」这个最常做的操作
 * 会顺手把密钥抹掉，且没有任何报错 —— 用户下次发消息才收到 401。
 *
 * <h2>为什么没有校验注解</h2>
 * 项目约定：DTO 不加 {@code @Size} 等 Bean Validation，长度与格式校验走
 * Service 层的 {@code Texts} 与显式检查。这里保持一致，不在 DTO 另起一套。
 *
 * @param baseUrl        端点地址；空表示回落到环境变量
 * @param apiKey         密钥；null=不改动，空串=清空，非空=覆盖
 * @param chatModel      对话模型名；空表示回落到环境变量
 * @param temperature    采样温度 0~2；null 表示回落到环境变量
 * @param embeddingModel 嵌入模型名；空表示回落（改动需重启生效）
 */
public record SaveModelConfigRequest(String baseUrl,
                                     String apiKey,
                                     String chatModel,
                                     Double temperature,
                                     String embeddingModel) {
}
