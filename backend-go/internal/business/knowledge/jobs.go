package knowledge

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sync/atomic"
	"time"

	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
)

func digest(text string) string {
	sum := sha256.Sum256([]byte(text))
	return hex.EncodeToString(sum[:])
}

type Job struct {
	ID         string  `json:"id"`
	DocumentID string  `json:"documentId"`
	Replacing  bool    `json:"replacing"`
	Filename   string  `json:"filename"`
	Title      *string `json:"title"`
	Status     string  `json:"status"`
	Attempts   int     `json:"attempts"`
	Progress   int     `json:"progress"`
	Stage      string  `json:"stage"`
	Result     *string `json:"result"`
	Error      *string `json:"error"`
}
type Claim struct {
	Job     Job
	Token   string
	Payload []byte
}

// AcquireWork 同时执行后台共享模型预算、API预留与速率限制。nil 表示无容量。
type AcquireWork func(context.Context) (func(), error)
type Jobs struct {
	Store   *Store
	Acquire AcquireWork
	Stats   *Stats
}

const jobColumns = "id::text,document_id::text,replacing,filename,title,status,attempts,progress,stage,result,error"

func scanJob(row scanner, j *Job) error {
	return row.Scan(&j.ID, &j.DocumentID, &j.Replacing, &j.Filename, &j.Title, &j.Status, &j.Attempts, &j.Progress, &j.Stage, &j.Result, &j.Error)
}
func (j *Jobs) Submit(ctx context.Context, id, target, filename string, title *string, payload []byte) (Job, error) {
	if len(payload) == 0 || int64(len(payload)) > j.Store.Reader.Properties.MaxFileSizeBytes {
		return Job{}, unsupported("文件为空或超过知识库大小上限")
	}
	if chars(filename) > 300 || title != nil && chars(*title) > 300 {
		return Job{}, api.NewBusinessError(api.ValidationFailed, "文件名和标题不能超过 300 字符")
	}
	if id == "" {
		id = newID()
	}
	text := ""
	if title != nil {
		text = *title
	}
	hash, e := idempotency.Digest([]any{target, filename, text, digest(base64.StdEncoding.EncodeToString(payload))})
	if e != nil {
		return Job{}, e
	}
	var job Job
	e = j.Store.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, e := tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock(181801,1)"); e != nil {
			return e
		}
		var previous string
		e := tx.QueryRowContext(ctx, "SELECT digest FROM knowledge_index_job WHERE id=$1", id).Scan(&previous)
		if e == nil {
			if previous != hash {
				return api.NewBusinessError(api.IdempotencyKeyConflict, api.IdempotencyKeyConflict.Message())
			}
			return scanJob(tx.QueryRowContext(ctx, "SELECT "+jobColumns+" FROM knowledge_index_job WHERE id=$1", id), &job)
		}
		if !errors.Is(e, sql.ErrNoRows) {
			return e
		}
		var waiting int
		if e = tx.QueryRowContext(ctx, "SELECT count(*) FROM knowledge_index_job WHERE status IN ('PENDING','RUNNING')").Scan(&waiting); e != nil {
			return e
		}
		if waiting >= 20 {
			return api.NewBusinessError(api.RateLimited, "后台任务队列已满，请稍后再试")
		}
		document := target
		if target == "" {
			document = id
		} else {
			var exists bool
			if e = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM knowledge_document WHERE id=$1)", target).Scan(&exists); e != nil {
				return e
			}
			if !exists {
				return missingDocument(sql.ErrNoRows)
			}
		}
		return scanJob(tx.QueryRowContext(ctx, `INSERT INTO knowledge_index_job(id,document_id,replacing,filename,title,payload,digest) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING `+jobColumns, id, document, target != "", filename, title, payload, hash), &job)
	})
	return job, e
}
func (j *Jobs) Get(ctx context.Context, id string) (Job, error) {
	var job Job
	e := j.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return scanJob(c.QueryRowContext(ctx, "SELECT "+jobColumns+" FROM knowledge_index_job WHERE id=$1", id), &job)
	})
	if errors.Is(e, sql.ErrNoRows) {
		e = api.NewBusinessError(api.ResourceNotFound, "后台任务不存在")
	}
	return job, e
}
func (j *Jobs) Recent(ctx context.Context) ([]Job, error) {
	out := []Job{}
	e := j.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, "SELECT "+jobColumns+" FROM knowledge_index_job ORDER BY created_at DESC LIMIT 20")
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var job Job
			if e = scanJob(rows, &job); e != nil {
				return e
			}
			out = append(out, job)
		}
		return rows.Err()
	})
	return out, e
}
func (j *Jobs) Claim(ctx context.Context) (*Claim, error) {
	var claim *Claim
	e := j.Store.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, e := tx.ExecContext(ctx, `UPDATE knowledge_index_job SET status='FAILED',payload=NULL,error='执行重试次数已达上限',stage='执行失败',updated_at=now() WHERE status='RUNNING' AND lease_until<now() AND attempts>=3`); e != nil {
			return e
		}
		token := newID()
		var id string
		var payload []byte
		e := tx.QueryRowContext(ctx, `WITH candidate AS (SELECT id FROM knowledge_index_job WHERE attempts<3 AND ((status='PENDING' AND available_at<=now()) OR (status='RUNNING' AND lease_until<now())) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE knowledge_index_job j SET status='RUNNING',attempts=attempts+1,claim_token=$1,lease_until=now()+interval '60 seconds',progress=5,stage='准备文档',updated_at=now() FROM candidate c WHERE j.id=c.id RETURNING j.id::text,j.payload`, token).Scan(&id, &payload)
		if errors.Is(e, sql.ErrNoRows) {
			return nil
		}
		if e != nil {
			return e
		}
		var job Job
		if e = scanJob(tx.QueryRowContext(ctx, "SELECT "+jobColumns+" FROM knowledge_index_job WHERE id=$1", id), &job); e != nil {
			return e
		}
		claim = &Claim{job, token, payload}
		return nil
	})
	return claim, e
}
func (j *Jobs) Renew(ctx context.Context, c *Claim) (bool, error) {
	var count int64
	e := j.Store.Pool.WithConn(ctx, func(conn *sql.Conn) error {
		r, e := conn.ExecContext(ctx, `UPDATE knowledge_index_job SET lease_until=now()+interval '60 seconds' WHERE id=$1 AND claim_token=$2 AND status='RUNNING' AND lease_until>=now()`, c.Job.ID, c.Token)
		if e != nil {
			return e
		}
		count, e = r.RowsAffected()
		return e
	})
	return count == 1, e
}
func (j *Jobs) progress(ctx context.Context, c *Claim, progress int, stage string) error {
	return j.exec(ctx, `UPDATE knowledge_index_job SET progress=$3,stage=$4,updated_at=now() WHERE id=$1 AND claim_token=$2 AND status='RUNNING'`, c.Job.ID, c.Token, progress, stage)
}
func (j *Jobs) exec(ctx context.Context, query string, args ...any) error {
	return j.Store.Pool.WithConn(ctx, func(conn *sql.Conn) error { _, e := conn.ExecContext(ctx, query, args...); return e })
}
func (j *Jobs) fail(ctx context.Context, c *Claim, cause error) error {
	retry := c.Job.Attempts < 3
	var business *api.BusinessError
	message := "索引暂时失败，请检查模型连接或稍后重试"
	if errors.As(cause, &business) {
		code := business.Code()
		retry = retry && (code == api.BusinessConflict || code == api.AIUpstreamError || code == api.AITimeout)
		message = code.Message()
	}
	status, stage := "FAILED", "执行失败"
	if retry {
		status, stage = "PENDING", "等待重试"
	}
	if j.Stats != nil {
		j.Stats.Count("jobs.attempts", map[bool]string{true: "retry", false: "failed"}[retry])
	}
	return j.exec(ctx, `UPDATE knowledge_index_job SET status=$3,stage=$4,error=$5,payload=CASE WHEN $6 THEN payload ELSE NULL END,lease_until=NULL,available_at=now()+interval '5 seconds',updated_at=now() WHERE id=$1 AND claim_token=$2 AND status='RUNNING' AND lease_until>=clock_timestamp()`, c.Job.ID, c.Token, status, stage, message, retry)
}
func (j *Jobs) Commit(ctx context.Context, c *Claim, l *coordination.Lease, u Upload, chunks []Chunk) error {
	return j.Store.Pool.WithManagedTx(ctx, sql.TxOptions{}, func(tx *database.Transaction) error {
		var id string
		e := tx.SQL().QueryRowContext(ctx, `SELECT id::text FROM knowledge_index_job WHERE id=$1 AND claim_token=$2 AND status='RUNNING' AND lease_until>=clock_timestamp() FOR UPDATE`, c.Job.ID, c.Token).Scan(&id)
		if errors.Is(e, sql.ErrNoRows) {
			return coordination.ErrLeaseLost
		}
		if e != nil {
			return e
		}
		if e = l.Fence(ctx, tx); e != nil {
			return e
		}
		d, e := j.Store.persist(ctx, tx.SQL(), c.Job.DocumentID, c.Job.Replacing, u, chunks)
		if e != nil {
			return e
		}
		result, e := json.Marshal(d)
		if e != nil {
			return e
		}
		_, e = tx.SQL().ExecContext(ctx, `UPDATE knowledge_index_job SET status='SUCCEEDED',progress=100,stage='索引完成',result=$3,error=NULL,payload=NULL,lease_until=NULL,updated_at=now() WHERE id=$1 AND claim_token=$2`, c.Job.ID, c.Token, string(result))
		return e
	})
}
func (j *Jobs) RunNext(ctx context.Context) (bool, error) {
	if j.Acquire != nil {
		done, e := j.Acquire(ctx)
		if e != nil || done == nil {
			return false, e
		}
		defer done()
	}
	c, e := j.Claim(ctx)
	if e != nil || c == nil {
		return false, e
	}
	if j.Stats != nil {
		j.Stats.Active.Add(1)
		defer j.Stats.Active.Add(-1)
	}
	started := time.Now()
	if j.Stats != nil {
		defer func() { j.Stats.Duration("jobs.duration", "", time.Since(started)) }()
	}
	work, cancel := context.WithCancel(ctx)
	defer cancel()
	renewed := make(chan struct{})
	var lost atomic.Bool
	go func() {
		defer close(renewed)
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-work.Done():
				return
			case <-ticker.C:
				ok, e := j.Renew(work, c)
				if e != nil || !ok {
					lost.Store(true)
					cancel()
					return
				}
			}
		}
	}()
	defer func() { cancel(); <-renewed }()
	e = func() error {
		l, e := j.Store.lock(work, c.Job.DocumentID)
		if e != nil {
			return e
		}
		defer release(l)
		if e = j.progress(work, c, 10, "解析文档"); e != nil {
			return e
		}
		title := ""
		if c.Job.Title != nil {
			title = *c.Job.Title
		}
		u, e := j.Store.Reader.Prepare(work, c.Job.Filename, title, c.Payload)
		if e != nil {
			return e
		}
		if e = j.progress(work, c, 40, "计算嵌入"); e != nil {
			return e
		}
		chunks, e := j.Store.PrepareChunks(work, u, c.Job.DocumentID)
		if e != nil {
			return e
		}
		if e = j.progress(work, c, 80, "保存索引"); e != nil {
			return e
		}
		return j.Commit(work, c, l, u, chunks)
	}()
	if e != nil {
		if lost.Load() || errors.Is(e, coordination.ErrLeaseLost) {
			if j.Stats != nil {
				j.Stats.Count("jobs.attempts", "lease_lost")
			}
			return true, nil
		}
		cleanup, stop := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer stop()
		return true, j.fail(cleanup, c, e)
	}
	if j.Stats != nil {
		j.Stats.Count("jobs.attempts", "success")
	}
	return true, nil
}
func (j *Jobs) Cleanup(ctx context.Context) error {
	return j.exec(ctx, `DELETE FROM knowledge_index_job WHERE status IN ('SUCCEEDED','FAILED') AND updated_at<now()-interval '7 days'`)
}
