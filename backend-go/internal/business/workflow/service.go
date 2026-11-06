// Package workflow 仅执行固定只读报告，不持有模型或Agent工具。
package workflow

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/database"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
)

type Definition struct {
	ID             string    `json:"id"`
	Name           string    `json:"name"`
	Kind           string    `json:"kind"`
	Timezone       string    `json:"timezone"`
	LocalTime      string    `json:"localTime"`
	Weekday        int       `json:"weekday"`
	Enabled        bool      `json:"enabled"`
	MaxRunsPerDay  int       `json:"maxRunsPerDay"`
	MaxItems       int       `json:"maxItems"`
	MaxOutputChars int       `json:"maxOutputChars"`
	NextRunAt      time.Time `json:"nextRunAt"`
}
type Change struct {
	Name           *string `json:"name"`
	Kind           *string `json:"kind"`
	Timezone       *string `json:"timezone"`
	LocalTime      *string `json:"localTime"`
	Weekday        *int    `json:"weekday"`
	Enabled        *bool   `json:"enabled"`
	MaxRunsPerDay  *int    `json:"maxRunsPerDay"`
	MaxItems       *int    `json:"maxItems"`
	MaxOutputChars *int    `json:"maxOutputChars"`
}
type Attempt struct {
	Attempt    int        `json:"attempt"`
	Status     string     `json:"status"`
	StartedAt  time.Time  `json:"startedAt"`
	FinishedAt *time.Time `json:"finishedAt"`
	Error      *string    `json:"error"`
}
type Execution struct {
	ID             string    `json:"id"`
	WorkflowID     string    `json:"workflowId"`
	ScheduledFor   time.Time `json:"scheduledFor"`
	Source         string    `json:"source"`
	Status         string    `json:"status"`
	Attempts       int       `json:"attempts"`
	ModelCalls     int       `json:"modelCalls"`
	Report         *string   `json:"report"`
	Error          *string   `json:"error"`
	RunID          *string   `json:"runId"`
	ConversationID *string   `json:"conversationId"`
	History        []Attempt `json:"history"`
}
type Snapshot struct {
	Name           string `json:"name"`
	Kind           string `json:"kind"`
	Timezone       string `json:"timezone"`
	MaxItems       int    `json:"max_items"`
	MaxOutputChars int    `json:"max_output_chars"`
}
type Service struct {
	Pool     *database.Pool
	Calendar *calendar.Events
}

func invalid(message string) error { return api.NewBusinessError(api.ValidationFailed, message) }
func missing() error {
	return api.NewBusinessError(api.ResourceNotFound, "计划或执行记录不存在")
}

type scanner interface{ Scan(...any) error }

const definitionColumns = "id::text,name,kind,timezone,to_char(local_time,'HH24:MI'),weekday,enabled,max_runs_per_day,max_items,max_output_chars,next_run_at"

func scanDefinition(r scanner) (v Definition, e error) {
	e = r.Scan(&v.ID, &v.Name, &v.Kind, &v.Timezone, &v.LocalTime, &v.Weekday, &v.Enabled, &v.MaxRunsPerDay, &v.MaxItems, &v.MaxOutputChars, &v.NextRunAt)
	return
}
func (s *Service) Get(ctx context.Context, id string) (v Definition, e error) {
	e = s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		v, e = scanDefinition(c.QueryRowContext(ctx, "SELECT "+definitionColumns+" FROM workflow WHERE id=$1", id))
		return e
	})
	if errors.Is(e, sql.ErrNoRows) {
		e = missing()
	}
	return
}
func (s *Service) List(ctx context.Context) (any, error) {
	items := []Definition{}
	var total int
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		if e := c.QueryRowContext(ctx, "SELECT count(*) FROM workflow").Scan(&total); e != nil {
			return e
		}
		rows, e := c.QueryContext(ctx, "SELECT "+definitionColumns+" FROM workflow ORDER BY created_at DESC LIMIT 100")
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			v, e := scanDefinition(rows)
			if e != nil {
				return e
			}
			items = append(items, v)
		}
		return rows.Err()
	})
	return map[string]any{"items": items, "total": total}, e
}
func (s *Service) Save(ctx context.Context, id string, change Change) (v Definition, e error) {
	e = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, e := tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock(350046)"); e != nil {
			return e
		}
		v = Definition{Name: "", Kind: "MORNING_BRIEF", Timezone: "Asia/Shanghai", LocalTime: "09:00", Weekday: 1, MaxRunsPerDay: 3, MaxItems: 30, MaxOutputChars: 10000}
		if id != "" {
			var e error
			v, e = scanDefinition(tx.QueryRowContext(ctx, "SELECT "+definitionColumns+" FROM workflow WHERE id=$1", id))
			if errors.Is(e, sql.ErrNoRows) {
				return missing()
			}
			if e != nil {
				return e
			}
		} else {
			var count int
			if e := tx.QueryRowContext(ctx, "SELECT count(*) FROM workflow").Scan(&count); e != nil {
				return e
			}
			if count >= 100 {
				return invalid("最多100个计划")
			}
		}
		if change.Name != nil {
			v.Name = strings.TrimSpace(*change.Name)
		}
		if change.Kind != nil {
			v.Kind = *change.Kind
		}
		if change.Timezone != nil {
			v.Timezone = *change.Timezone
		}
		if change.LocalTime != nil {
			v.LocalTime = *change.LocalTime
		}
		if change.Weekday != nil {
			v.Weekday = *change.Weekday
		}
		if change.Enabled != nil {
			v.Enabled = *change.Enabled
		}
		if change.MaxRunsPerDay != nil {
			v.MaxRunsPerDay = *change.MaxRunsPerDay
		}
		if change.MaxItems != nil {
			v.MaxItems = *change.MaxItems
		}
		if change.MaxOutputChars != nil {
			v.MaxOutputChars = *change.MaxOutputChars
		}
		if v.Name == "" || len(utf16.Encode([]rune(v.Name))) > 100 || (v.Kind != "MORNING_BRIEF" && v.Kind != "WEEKLY_REVIEW" && v.Kind != "KNOWLEDGE_REVIEW") || len(v.Timezone) > 100 || v.Weekday < 1 || v.Weekday > 7 || v.MaxRunsPerDay < 1 || v.MaxRunsPerDay > 24 || v.MaxItems < 1 || v.MaxItems > 100 || v.MaxOutputChars < 1000 || v.MaxOutputChars > 20000 {
			return invalid("请检查计划名称、类型、时间和消费上限")
		}
		if _, e := time.Parse("15:04", v.LocalTime); e != nil {
			return invalid("请选择有效时间")
		}
		if _, e := time.LoadLocation(v.Timezone); e != nil {
			return invalid("请选择有效IANA时区")
		}
		var zone bool
		if e := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=$1)", v.Timezone).Scan(&zone); e != nil {
			return e
		}
		if !zone {
			return invalid("请选择有效IANA时区")
		}
		args := []any{v.Name, v.Kind, v.Timezone, v.LocalTime, v.Weekday, v.Enabled, v.MaxRunsPerDay, v.MaxItems, v.MaxOutputChars}
		var err error
		if id == "" {
			v, err = scanDefinition(tx.QueryRowContext(ctx, "INSERT INTO workflow(name,kind,timezone,local_time,weekday,enabled,max_runs_per_day,max_items,max_output_chars) VALUES($1,$2,$3,$4::time,$5,$6,$7,$8,$9) RETURNING "+definitionColumns, args...))
		} else {
			args = append(args, id)
			v, err = scanDefinition(tx.QueryRowContext(ctx, "UPDATE workflow SET name=$1,kind=$2,timezone=$3,local_time=$4::time,weekday=$5,enabled=$6,max_runs_per_day=$7,max_items=$8,max_output_chars=$9 WHERE id=$10 RETURNING "+definitionColumns, args...))
		}
		return err
	})
	return
}

const executionColumns = "SELECT e.id::text,e.workflow_id::text,e.scheduled_for,e.source,e.status,e.attempts,e.model_calls,e.report,e.error,e.run_id,r.conversation_id FROM workflow_execution e LEFT JOIN chat_run r ON r.id=e.run_id"

func scanExecution(r scanner) (v Execution, e error) {
	v.History = []Attempt{}
	e = r.Scan(&v.ID, &v.WorkflowID, &v.ScheduledFor, &v.Source, &v.Status, &v.Attempts, &v.ModelCalls, &v.Report, &v.Error, &v.RunID, &v.ConversationID)
	return
}
func (s *Service) withHistory(ctx context.Context, items []Execution) error {
	if len(items) == 0 {
		return nil
	}
	ids := make([]string, len(items))
	byID := make(map[string]int, len(items))
	for i := range items {
		ids[i] = items[i].ID
		byID[items[i].ID] = i
	}
	return s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, "SELECT execution_id::text,attempt,status,started_at,finished_at,error FROM workflow_attempt WHERE execution_id=ANY($1::uuid[]) ORDER BY execution_id,attempt", ids)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var id string
			var a Attempt
			if e := rows.Scan(&id, &a.Attempt, &a.Status, &a.StartedAt, &a.FinishedAt, &a.Error); e != nil {
				return e
			}
			i := byID[id]
			items[i].History = append(items[i].History, a)
		}
		return rows.Err()
	})
}
func (s *Service) Execution(ctx context.Context, id string) (v Execution, e error) {
	e = s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		v, e = scanExecution(c.QueryRowContext(ctx, executionColumns+" WHERE e.id=$1", id))
		return e
	})
	if errors.Is(e, sql.ErrNoRows) {
		return v, missing()
	}
	if e == nil {
		items := []Execution{v}
		e = s.withHistory(ctx, items)
		v = items[0]
	}
	return
}
func (s *Service) Run(ctx context.Context, id string) (Execution, error) {
	if _, e := s.Get(ctx, id); e != nil {
		return Execution{}, e
	}
	var run string
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, "SELECT enqueue_workflow($1,true)::text", id).Scan(&run)
	})
	if e != nil {
		return Execution{}, e
	}
	return s.Execution(ctx, run)
}
func (s *Service) Retry(ctx context.Context, id string) (Execution, error) {
	e := s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		var status string
		var attempts int
		if e := tx.QueryRowContext(ctx, "SELECT status,attempts FROM workflow_execution WHERE id=$1 FOR UPDATE", id).Scan(&status, &attempts); errors.Is(e, sql.ErrNoRows) {
			return missing()
		} else if e != nil {
			return e
		}
		if status != "FAILED" || attempts >= 3 {
			return api.NewBusinessError(api.BusinessConflict, "仅能重试未达到3次上限的失败期次")
		}
		_, e := tx.ExecContext(ctx, "UPDATE workflow_execution SET status='PENDING',source='MANUAL',error=NULL,available_at=now(),updated_at=now() WHERE id=$1", id)
		return e
	})
	if e != nil {
		return Execution{}, e
	}
	return s.Execution(ctx, id)
}
func (s *Service) History(ctx context.Context, id string) (any, error) {
	if _, e := s.Get(ctx, id); e != nil {
		return nil, e
	}
	items := []Execution{}
	var total int
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		if e := c.QueryRowContext(ctx, "SELECT count(*) FROM workflow_execution WHERE workflow_id=$1", id).Scan(&total); e != nil {
			return e
		}
		rows, e := c.QueryContext(ctx, executionColumns+" WHERE e.workflow_id=$1 ORDER BY e.created_at DESC,e.id DESC LIMIT 50", id)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			v, e := scanExecution(rows)
			if e != nil {
				return e
			}
			items = append(items, v)
		}
		return rows.Err()
	})
	if e == nil {
		e = s.withHistory(ctx, items)
	}
	return map[string]any{"items": items, "total": total}, e
}
func (s *Service) Dispatch(ctx context.Context) error {
	return s.Pool.WithConn(ctx, func(c *sql.Conn) error { _, e := c.ExecContext(ctx, "SELECT dispatch_workflows()"); return e })
}
func (s *Service) RunNext(ctx context.Context) (bool, error) {
	var id, token, raw string
	var scheduled time.Time
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, "SELECT id::text,claim_token::text,definition::text,scheduled_for FROM claim_workflow()").Scan(&id, &token, &raw, &scheduled)
	})
	if errors.Is(e, sql.ErrNoRows) {
		return false, nil
	}
	if e != nil {
		return false, e
	}
	var definition Snapshot
	var report string
	cause := json.Unmarshal([]byte(raw), &definition)
	if cause == nil {
		report, cause = s.Report(ctx, definition, scheduled)
	}
	var errMessage any
	if cause != nil {
		errMessage = "只读报告暂时失败，将在上限内重试"
	}
	var committed bool
	e = s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, "SELECT finish_workflow($1,$2,$3,$4)", id, token, report, errMessage).Scan(&committed)
	})
	return true, e
}
func (s *Service) Start(parent context.Context, logger *slog.Logger) func() {
	ctx, cancel := context.WithCancel(parent)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				work, stop := context.WithTimeout(ctx, 20*time.Second)
				e := s.Dispatch(work)
				if e == nil {
					_, e = s.RunNext(work)
				}
				stop()
				if e != nil && ctx.Err() == nil {
					logger.Warn("只读工作流本轮未完成")
				}
			}
		}
	}()
	return func() { cancel(); wg.Wait() }
}
func (s *Service) Report(ctx context.Context, d Snapshot, scheduled time.Time) (string, error) {
	zone, e := time.LoadLocation(d.Timezone)
	if e != nil {
		return "", e
	}
	local := scheduled.In(zone)
	day := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, zone)
	from, to := day, day.AddDate(0, 0, 1)
	weekly := d.Kind == "WEEKLY_REVIEW"
	if weekly {
		from, to = day.AddDate(0, 0, -7), day
	}
	lines := []string{d.Name + "（固定只读报告）", fmt.Sprintf("时区：%s；范围：[%s, %s)", d.Timezone, from.UTC().Format(time.RFC3339), to.UTC().Format(time.RFC3339)), fmt.Sprintf("零模型调用、零工具调用；数据项上限 %d，输出字符上限 %d", d.MaxItems, d.MaxOutputChars)}
	priority, secondary := []string{}, []string{}
	query := func(sqlText, prefix string, args ...any) error {
		return s.Pool.WithConn(ctx, func(c *sql.Conn) error {
			rows, e := c.QueryContext(ctx, sqlText, args...)
			if e != nil {
				return e
			}
			defer rows.Close()
			for rows.Next() {
				var name string
				var at time.Time
				if e := rows.Scan(&name, &at); e != nil {
					return e
				}
				secondary = append(secondary, prefix+name+" · "+at.UTC().Format(time.RFC3339))
			}
			return rows.Err()
		})
	}
	if d.Kind == "KNOWLEDGE_REVIEW" {
		lines = append(lines, "资料巡检：仅检查本地资料和采集状态，不访问来源网页。")
		e = query("SELECT title,created_at FROM knowledge_document ORDER BY created_at DESC,id LIMIT $1", "资料：", d.MaxItems+1)
		if e == nil {
			e = s.Pool.WithConn(ctx, func(c *sql.Conn) error {
				rows, e := c.QueryContext(ctx, "SELECT canonical_url,coalesce(last_error,'正在索引') FROM knowledge_capture WHERE last_error IS NOT NULL OR pending_job_id IS NOT NULL ORDER BY (last_error IS NOT NULL) DESC,last_checked_at DESC NULLS LAST,id LIMIT $1", d.MaxItems+1)
				if e != nil {
					return e
				}
				defer rows.Close()
				for rows.Next() {
					var url, status string
					if e := rows.Scan(&url, &status); e != nil {
						return e
					}
					priority = append(priority, "采集："+url+" · "+status)
				}
				return rows.Err()
			})
		}
		lines = append(lines, "按采集状态、资料分区轮流展示，采集失败优先；空余名额自动补足。")
	} else {
		if weekly {
			e = query("SELECT title,completed_at FROM task WHERE status='DONE' AND completed_at>=$1 AND completed_at<$2 ORDER BY completed_at,id LIMIT $3", "已完成：", from, to, d.MaxItems+1)
		} else {
			e = query("SELECT title,due_at FROM task WHERE status<>'DONE' AND due_at<$1 ORDER BY due_at,id LIMIT $2", "待办：", to, d.MaxItems+1)
		}
		if e == nil {
			page, err := s.Calendar.Search(ctx, &from, &to, 0, min(101, d.MaxItems+1))
			e = err
			if e == nil {
				for _, v := range page.Items {
					if v.StartsAt.Before(to) {
						priority = append(priority, "日程："+v.Title+" · "+v.StartsAt.In(zone).Format(time.RFC3339)+" — "+v.EndsAt.In(zone).Format(time.RFC3339))
					}
				}
				if page.Total > int64(len(page.Items)) {
					lines = append(lines, "日程候选已按上限截断。")
				}
			}
		}
		lines = append(lines, "按日程、任务分区轮流展示，日程优先；空余名额自动补足。")
	}
	if e != nil {
		return "", e
	}
	if len(priority) == 0 && len(secondary) == 0 {
		lines = append(lines, "该范围暂无匹配记录。")
	}
	shown := 0
	for i := 0; i < max(len(priority), len(secondary)) && shown < d.MaxItems; i++ {
		if i < len(priority) && shown < d.MaxItems {
			lines = append(lines, "• "+priority[i])
			shown++
		}
		if i < len(secondary) && shown < d.MaxItems {
			lines = append(lines, "• "+secondary[i])
			shown++
		}
	}
	if len(priority)+len(secondary) > d.MaxItems {
		lines = append(lines, "[达到数据项上限，部分记录未展示]")
	}
	report := strings.Join(lines, "\n")
	runes := []rune(report)
	suffix := "\n[达到输出上限，部分内容已截断]"
	if len(runes) > d.MaxOutputChars {
		report = string(runes[:d.MaxOutputChars-len([]rune(suffix))]) + suffix
	}
	return report, nil
}
