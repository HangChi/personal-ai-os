package knowledge

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type ReviewClaim struct {
	DocumentID, Token string
	Revision          int64
	Cursor, Attempts  int
}
type ReviewJobs struct {
	Store      *Store
	Governance *Governance
	Reviewer   *EvidenceReviewer
	Recall     CandidateRecall
	Acquire    AcquireWork
	Timeout    time.Duration
	Stats      *Stats
}

func (j *ReviewJobs) Claim(ctx context.Context) (*ReviewClaim, error) {
	claim := ReviewClaim{Token: newID()}
	e := j.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, `WITH ready AS (SELECT document_id FROM knowledge_review_job WHERE next_attempt_at<=now() ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE knowledge_review_job j SET next_attempt_at=now()+interval '60 seconds',lease_token=$1,attempts=attempts+1 FROM ready WHERE j.document_id=ready.document_id RETURNING j.document_id::text,j.revision,j.chunk_cursor,j.attempts`, claim.Token).Scan(&claim.DocumentID, &claim.Revision, &claim.Cursor, &claim.Attempts)
	})
	if errors.Is(e, sql.ErrNoRows) {
		return nil, nil
	}
	if e != nil {
		return nil, e
	}
	return &claim, nil
}
func (j *ReviewJobs) Finish(ctx context.Context, c *ReviewClaim, sources []string, findings []Disagreement, done, failed bool) error {
	if !contains(sources, c.DocumentID) {
		sources = append(sources, c.DocumentID)
	}
	return j.Store.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if e := lockSources(ctx, tx, sources); e != nil {
			return e
		}
		var id string
		e := tx.QueryRowContext(ctx, `SELECT j.document_id::text FROM knowledge_review_job j JOIN knowledge_source s ON s.document_id=j.document_id AND s.revision=j.revision WHERE j.document_id=$1 AND j.revision=$2 AND j.lease_token=$3 AND j.next_attempt_at>clock_timestamp() FOR UPDATE OF j`, c.DocumentID, c.Revision, c.Token).Scan(&id)
		if errors.Is(e, sql.ErrNoRows) {
			if j.Stats != nil {
				j.Stats.Count("review.results", "lease_lost")
			}
			return nil
		}
		if e != nil {
			return e
		}
		state := "PARTIAL"
		if failed {
			state = "UNAVAILABLE"
		} else if done {
			state = "CHECKED_CANDIDATES"
		} else if e = j.Governance.recordTx(ctx, tx, findings); e != nil {
			return e
		}
		if _, e = tx.ExecContext(ctx, "UPDATE knowledge_source SET review_state=$1 WHERE document_id=$2 AND revision=$3", state, id, c.Revision); e != nil {
			return e
		}
		switch {
		case failed:
			_, e = tx.ExecContext(ctx, "UPDATE knowledge_review_job SET next_attempt_at=CASE WHEN attempts>=3 THEN 'infinity'::timestamptz ELSE now()+interval '5 minutes' END,last_error='Review failed' WHERE document_id=$1", id)
		case done:
			_, e = tx.ExecContext(ctx, "DELETE FROM knowledge_review_job WHERE document_id=$1", id)
		default:
			_, e = tx.ExecContext(ctx, "UPDATE knowledge_review_job SET chunk_cursor=chunk_cursor+1,next_attempt_at=now(),attempts=0,last_error=NULL WHERE document_id=$1", id)
		}
		if e == nil && j.Stats != nil {
			j.Stats.Count("review.results", state)
		}
		return e
	})
}
func (j *ReviewJobs) RunNext(ctx context.Context) (bool, error) {
	if j.Acquire != nil {
		release, e := j.Acquire(ctx)
		if e != nil || release == nil {
			return false, e
		}
		defer release()
	}
	c, e := j.Claim(ctx)
	if e != nil || c == nil {
		return false, e
	}
	sources := []string{c.DocumentID}
	execute := func() error {
		hits, e := j.Governance.documentChunk(ctx, c.DocumentID, c.Cursor)
		if e != nil {
			return e
		}
		metadata, e := j.Governance.sourceMap(ctx, []string{c.DocumentID})
		if e != nil {
			return e
		}
		s, ok := metadata[c.DocumentID]
		if !ok || s.Revision != c.Revision {
			return nil
		}
		if len(hits) == 0 {
			return j.Finish(ctx, c, sources, nil, true, false)
		}
		pool := []Hit{hits[0]}
		raw, e := j.Recall.Candidates(ctx, truncate(hits[0].Content, 1800), 20)
		if e != nil {
			return e
		}
		raw, e = j.Governance.Enrich(ctx, raw)
		if e != nil {
			return e
		}
		for _, h := range raw {
			if h.DocumentID != c.DocumentID && len(pool) < 12 {
				pool = append(pool, h)
				if !contains(sources, h.DocumentID) {
					sources = append(sources, h.DocumentID)
				}
			}
		}
		findings := []Disagreement{}
		if len(pool) > 1 {
			timeout := j.Timeout
			if timeout == 0 {
				timeout = 12 * time.Second
			}
			review, e := j.Reviewer.Review(ctx, "核查新增资料与已有资料对同一事实的不同说法；所有当前资料均为核查对象。", pool, max(time.Second, min(30*time.Second, timeout)))
			if e != nil {
				return e
			}
			findings = review.Conflicts
		}
		return j.Finish(ctx, c, sources, findings, false, false)
	}
	if e = execute(); e != nil {
		cleanup, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		return true, j.Finish(cleanup, c, sources, nil, false, true)
	}
	return true, nil
}
