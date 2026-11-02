# 实现方案

继续使用 Spring MVC、Spring AI、PostgreSQL、Vue/Pinia，不添加运行时依赖。

Flyway V33 新增 agent_question 与 agent_followup，并扩展 chat_run 的 WAITING_USER 状态。AgentInteractionService 管理持久化问题、输入队列和进程内控制信号；工具通过现有 ToolContributor 注册 askUser。只有正常后台任务能创建结构化问题。

AgentControlSignal 在模型与工具调用入口以及回答增量处交还控制。ChatCompletionService 将此分支发为 yield，不作为模型错误；ChatRunService 保存等待状态，或应用引导后以相同 run id 续跑。已经成功的写操作沿用原幂等键。正在执行中的工具先返回，随后才接受引导。

排队消息使用自身稳定 id 创建新执行；只有原任务 COMPLETED 时按序调度，停止、等待回答、等待授权和异常暂停均保留队列。启动时恢复运行中的任务并补调度持久化队列。建执行后但标记队列前的崩溃窗口通过检查同 id 执行记录补记，避免重发。

界面显示独立的澄清问题卡片，选项单次点击提交，自由输入支持自定义答案。处理方式放在「设置 → 对话」，通过既有偏好 store 保存至 localStorage，缺省排队；输入区直接按设置发送，同时保留停止按钮。待处理队列在输入框上方显示，支持取消、行内编辑，以及复用原记录转为当前运行任务的引导。队列修改与任务调度由 ChatRunService 串行协调；已开始处理的记录拒绝编辑或取消。状态刷新从服务端快照读取；下一条排队任务启动后前端自动追赶。

授权仍沿用原确认 API 与单次点击卡片；答案、补充要求或选项点击均不会替代破坏性操作授权。
