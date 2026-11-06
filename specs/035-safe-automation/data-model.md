# Data Model
- calendar_ics_identity：UID（必填、最多512字符）、event_id（唯一外键）；同UID只去重不隐式覆盖。
- knowledge_capture：规范URL（唯一、最多2048字符）、当前成功document_id/版本/摘要、pending job、检查时间；version保存有界正文（最多1MiB）、标题、来源、摘要、索引任务和成功状态。
- workflow：name（必填、最多100字符）、kind（MORNING_BRIEF/WEEKLY_REVIEW/KNOWLEDGE_REVIEW）、timezone（有效IANA）、local_time、weekday（1..7）、enabled、max_runs_per_day（1..24）、max_items（1..100）、max_output_chars（1000..20000）、next_run_at。
- workflow_execution：workflow+scheduled_for唯一，状态/attempts（最多3）、claim token和到期、只读结果及Run关联；重试保留一期身份。失败不推进成功。
- runtime_heartbeat：instance_id、backend（JAVA/GO）、role、started_at/last_seen_at；30秒续写、超过90秒为失联、7天过期清理，不与runtime_lease混淆。
- maintenance_check：类型BUSINESS_JSON/EXTERNAL_DRILL、原始字节SHA-256、状态VALID/INVALID/UNVERIFIED、计数/有界问题、时间；保留最近200条，不保存上传的完整备份。EXTERNAL_DRILL仅为外部报告，非服务独立背书。
