-- 只在脚本自己创建的临时数据库填充虚构数据；不包含真实用户、附件或密钥。
BEGIN;
INSERT INTO task(id,title,description,status,priority,due_at)
VALUES('00000000-0000-0000-0000-000000000042','恢复任务😀','中文与组合字符é','TODO','HIGH','2026-11-03T08:30:00+08:00');
INSERT INTO chat_session(conversation_id,title) VALUES('backup-fixture','恢复会话🔐');
INSERT INTO chat_message(conversation_id,message_type,content,sequence_no)
VALUES('backup-fixture','USER','合成备份消息',1);
INSERT INTO memory(id,content,content_hash,created_at,updated_at,pinned,embedding,embedding_model)
VALUES('00000000-0000-0000-0000-000000000043','恢复记忆😀',encode(digest('恢复记忆😀','sha256'),'hex'),
       '2026-11-03T00:00:00Z','2026-11-03T00:00:00Z',true,
       (ARRAY[1::real] || array_fill(0::real,ARRAY[1023]))::vector,'fixture-embedding');
INSERT INTO ai_model_profile(id,name,base_url,api_key,active)
VALUES('00000000-0000-0000-0000-000000000044','恢复模型','https://fixture.invalid','fixture-opaque-value-not-a-real-key',false);
INSERT INTO knowledge_index_job(id,document_id,replacing,filename,payload,digest)
VALUES('00000000-0000-0000-0000-000000000045','00000000-0000-0000-0000-000000000046',false,'合成输入.bin',
       decode('000102ff00e4b8ad','hex'),encode(digest(decode('000102ff00e4b8ad','hex'),'sha256'),'hex'));
COMMIT;
