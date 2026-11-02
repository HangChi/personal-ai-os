package com.personalos.common.ai;

import java.util.Map;

/** 在安全边界交还控制权，不属于模型错误或工具失败。 */
public final class AgentControlSignal extends RuntimeException {
    public AgentControlSignal() { super("等待用户输入或应用引导"); }
    public static void checkpoint(Map<String, Object> context) {
        if (context != null && context.get(ToolContextKeys.CONTROL) instanceof Runnable control) control.run();
    }
    public static boolean causedBy(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AgentControlSignal) return true;
        }
        return false;
    }
}
