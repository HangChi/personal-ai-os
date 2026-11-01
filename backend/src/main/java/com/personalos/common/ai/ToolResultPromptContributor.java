package com.personalos.common.ai;

import org.springframework.stereotype.Component;

/** Keep claims about system mutations tied to actual tool results. */
@Component
public class ToolResultPromptContributor implements PromptContributor {
    @Override
    public String contribute() {
        return "[操作结果约束]\n用户要求新增、修改、删除或安排事项时，必须调用对应工具，不能只生成说明文字。"
                + "只有本轮对应写工具成功返回，才能声称已创建、已修改或已删除；历史回复、查询结果和计划均不等于执行成功。"
                + "需要人工确认时说明待确认，失败或未调用时如实说明未完成。日程信息齐全时直接调用创建日程工具；"
                + "信息不足或日期指代不明确时询问，不能伪造记录、工具结果或成功状态。";
    }
}
