package com.personalos.export;

import java.util.List;

/**
 * 导出贡献者 SPI：各业务模块告知「本模块导出什么」。
 *
 * <p><b>为什么用 SPI 而不是让导出服务直接依赖五个模块</b>：那样导出服务会 import 全部业务
 * Service，将来加第六个模块就要改它 —— 而「改一个原本正确的类」正是回归的来源。
 * 这与既有的 {@code ToolContributor} / {@code PromptContributor} 是同一套思路。
 *
 * <p>实现方最清楚「本模块导出什么算完整」，所以组装权在模块自己手里，
 * 导出服务只负责拼装与计数。
 */
public interface ExportContributor {

    /**
     * 导出文件中的顶层字段名（也用作 {@code counts} 的键）。
     *
     * <p>必须是稳定的英文小驼峰：用户可能写脚本消费这个文件，改键名等于破坏契约。
     */
    String key();

    /**
     * 本模块的数据快照。
     *
     * <p>返回值会被 Jackson 直接序列化，因此应当是**对外 DTO**，不是 JPA Entity
     * （Entity 带有懒加载关联与内部字段，序列化会失败或泄漏实现细节）。
     */
    List<?> snapshot();
}
