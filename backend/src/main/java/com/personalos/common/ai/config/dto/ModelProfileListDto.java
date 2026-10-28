package com.personalos.common.ai.config.dto;

import java.util.List;

/**
 * 配置列表 + 当前生效者。
 *
 * <h2>为什么把 {@code activeId} 单独给出来</h2>
 * 列表里每一项都带 {@code active} 布尔值，理论上前端可以自己 `find` 出来。
 * 但「哪一套生效」是这份数据里**唯一一个必须唯一**的事实，让前端从数组里
 * 推断会给出两个可能不一致的答案（例如两行都是 true，或有零行）。
 * 后端在这里明确给出唯一答案，前端直接用它渲染「当前生效」徽章。
 *
 * <p>{@code activeId} 为 null 表示「没有生效配置」—— 此时聊天回落到
 * 环境变量那套，界面应说明这一点而不是显示空白。
 *
 * @param profiles 全部配置，已按展示顺序排列
 * @param activeId 当前生效的配置 id；null 表示没有任何配置生效
 */
public record ModelProfileListDto(List<ModelProfileDto> profiles, java.util.UUID activeId) {
}
