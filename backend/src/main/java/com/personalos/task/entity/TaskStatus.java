package com.personalos.task.entity;

/**
 * 任务状态。
 *
 * <p>只有三个取值，且刻意保持最小：状态越多，模型在自然语言里选错的概率越高。
 * {@code DOING} 的存在是为了让「进行中」不被强行表示为「未完成」。
 */
public enum TaskStatus {
    /** 待办 */
    TODO,
    /** 进行中 */
    DOING,
    /** 已完成 */
    DONE
}
