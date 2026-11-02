-- 文档后台索引：输入暂存于数据库；领取是短事务，完成标记与索引写入原子提交。
CREATE TABLE knowledge_index_job (
 id uuid PRIMARY KEY,
 document_id uuid NOT NULL,
 replacing boolean NOT NULL,
 filename varchar(300) NOT NULL,
 title varchar(300),
 payload bytea,
 digest varchar(64) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
 attempts integer NOT NULL DEFAULT 0,
 progress integer NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
 stage varchar(100) NOT NULL DEFAULT '等待执行',
 claim_token uuid,
 lease_until timestamptz,
 available_at timestamptz NOT NULL DEFAULT now(),
 result text,
 error varchar(300),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_knowledge_job_ready ON knowledge_index_job(available_at, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_knowledge_job_lease ON knowledge_index_job(lease_until) WHERE status = 'RUNNING';
