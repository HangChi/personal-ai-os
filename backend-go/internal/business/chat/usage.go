package chat

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"sort"
	"time"

	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/database"
	"personal-ai-os/backend-go/internal/platform/taskexec"
)

type UsageRecord struct {
	Model              string
	Prompt, Completion int
	Source             string
}
type DailyUsage struct {
	Date       string `json:"date"`
	Prompt     int64  `json:"promptTokens"`
	Completion int64  `json:"completionTokens"`
	Total      int64  `json:"totalTokens"`
	Turns      int64  `json:"turns"`
	Estimated  bool   `json:"estimated"`
}
type UsageStats struct {
	Days       int          `json:"days"`
	Prompt     int64        `json:"totalPrompt"`
	Completion int64        `json:"totalCompletion"`
	Total      int64        `json:"totalTokens"`
	Turns      int64        `json:"turns"`
	Actual     int64        `json:"actualTokens"`
	Estimated  int64        `json:"estimatedTokens"`
	HasData    bool         `json:"hasData"`
	Daily      []DailyUsage `json:"daily"`
}
type UsageService struct {
	Pool   *database.Pool
	Zone   *time.Location
	Now    func() time.Time
	Logger *slog.Logger
}

// resolveUsage 保留真实数据的优先级；全零与缺失 usage 必须走估算。
func resolveUsage(actual *model.Usage, name, sample string, chars int, reply string) UsageRecord {
	if actual != nil && actual.PromptTokens >= 0 && actual.CompletionTokens >= 0 && (actual.PromptTokens > 0 || actual.CompletionTokens > 0) {
		return UsageRecord{Model: name, Prompt: actual.PromptTokens, Completion: actual.CompletionTokens, Source: "ACTUAL"}
	}
	return UsageRecord{Prompt: chatcontext.ContentTokensFor(sample, chars), Completion: chatcontext.ContentTokens(reply), Source: "ESTIMATED"}
}

// Record 与 Java 相同：完成事件后的有界尽力记账，不继承 HTTP 取消，不重试。
func (u *UsageService) Record(ctx context.Context, executor *taskexec.Executor, id string, v UsageRecord) {
	if executor == nil || v.Prompt == 0 && v.Completion == 0 {
		return
	}
	at := u.Now().UTC()
	executor.Submit(ctx, func(post context.Context) error {
		post, cancel := context.WithTimeout(post, 5*time.Second)
		defer cancel()
		if err := u.Persist(post, id, v, at); err != nil && u.Logger != nil {
			u.Logger.WarnContext(post, "用量写入失败，不影响已完成回答")
		}
		return nil
	})
}

func (u *UsageService) Persist(ctx context.Context, id string, v UsageRecord, at time.Time) error {
	return u.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error { return (&postgresTx{tx: tx}).InsertUsage(ctx, id, v, at) })
}
func (t *postgresTx) InsertUsage(ctx context.Context, id string, v UsageRecord, at time.Time) error {
	if v.Prompt < 0 || v.Completion < 0 || (v.Source != "ACTUAL" && v.Source != "ESTIMATED") {
		return errors.New("用量无效")
	}
	if v.Prompt == 0 && v.Completion == 0 {
		return nil
	}
	_, err := t.tx.ExecContext(ctx, `INSERT INTO token_usage(conversation_id,model,prompt_tokens,completion_tokens,source,created_at) VALUES($1,NULLIF($2,''),$3,$4,$5,$6)`, id, v.Model, v.Prompt, v.Completion, v.Source, at)
	return err
}
func (u *UsageService) Latest(ctx context.Context, id string) (result *int, err error) {
	err = u.Pool.WithConn(ctx, func(c *sql.Conn) error {
		var n int
		e := c.QueryRowContext(ctx, `SELECT prompt_tokens FROM token_usage WHERE conversation_id=$1 AND source='ACTUAL' ORDER BY created_at DESC,id DESC LIMIT 1`, id).Scan(&n)
		if errors.Is(e, sql.ErrNoRows) {
			return nil
		}
		if e == nil {
			result = &n
		}
		return e
	})
	return
}
func (u *UsageService) Stats(ctx context.Context, days *int) (result UsageStats, err error) {
	result.Days = 30
	if days != nil {
		result.Days = min(365, max(1, *days))
	}
	result.Daily = []DailyUsage{}
	now := u.Now().In(u.Zone)
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, u.Zone)
	from, to := today.AddDate(0, 0, 1-result.Days), today.AddDate(0, 0, 1)
	// Go 的 time.Local 名称并非 PostgreSQL 时区名；逐行流式归入至多365个日期桶。
	// 分组使用同一应用时区，保留 DST 边界，不把当前UTC偏移误用于整段历史。
	daily := map[string]*DailyUsage{}
	actualDays := map[string]bool{}
	err = u.Pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, `SELECT created_at,prompt_tokens,completion_tokens,source FROM token_usage WHERE created_at >= $1 AND created_at < $2`, from, to)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var at time.Time
			var prompt, completion int64
			var source string
			if e = rows.Scan(&at, &prompt, &completion, &source); e != nil {
				return e
			}
			date := at.In(u.Zone).Format("2006-01-02")
			d := daily[date]
			if d == nil {
				d = &DailyUsage{Date: date}
				daily[date] = d
			}
			d.Prompt += prompt
			d.Completion += completion
			d.Total += prompt + completion
			d.Turns++
			result.Prompt += prompt
			result.Completion += completion
			result.Turns++
			if source == "ACTUAL" {
				result.Actual += prompt + completion
				actualDays[date] = true
			} else {
				result.Estimated += prompt + completion
				d.Estimated = true
			}
		}
		return rows.Err()
	})
	dates := make([]string, 0, len(daily))
	for date := range daily {
		dates = append(dates, date)
	}
	sort.Strings(dates)
	for _, date := range dates {
		d := daily[date]
		d.Estimated = d.Estimated && !actualDays[date] && d.Total > 0
		result.Daily = append(result.Daily, *d)
	}
	result.Total = result.Prompt + result.Completion
	result.HasData = len(result.Daily) > 0
	return
}

type ContextUsage struct {
	Prompt       int     `json:"promptTokens"`
	Budget       int     `json:"budgetTokens"`
	Window       int     `json:"contextWindow"`
	Estimated    bool    `json:"estimated"`
	Model        *string `json:"modelName"`
	WindowSource string  `json:"windowSource"`
	Input        int     `json:"inputLimitTokens"`
	Output       int     `json:"outputReserveTokens"`
	Margin       int     `json:"safetyMarginTokens"`
	Verified     int     `json:"verifiedInputTokens"`
	InputSource  string  `json:"inputLimitSource"`
	Ratio        float64 `json:"tokenEstimateRatio"`
}

func ContextFor(ctx context.Context, service *Service, b *chatcontext.Budget, a *Assembler, u *UsageService, id, thinking string) (v ContextUsage, err error) {
	name := ""
	if id != "" {
		var s Session
		s, err = service.Require(ctx, id)
		if err != nil {
			return
		}
		name = value(s.ChatModel)
	}
	w := b.Window(ctx, name)
	v.Window = w.Tokens
	v.WindowSource = w.Source
	v.Estimated = true
	if w.ModelName != "" {
		v.Model = ptr(w.ModelName)
	}
	if id != "" {
		var n *int
		n, err = u.Latest(ctx, id)
		if err != nil {
			return
		}
		if n != nil {
			v.Prompt = *n
			v.Estimated = false
		} else {
			v.Prompt, err = a.Estimate(ctx, id)
			if err != nil {
				return
			}
		}
	}
	v.Output = b.Output(w.Tokens, ThinkingBudget(thinking))
	v.Input = b.InputLimit(ctx, name, v.Output)
	v.Budget = min(b.ForWindow(w.Tokens), v.Input)
	v.Margin = b.Margin(w.Tokens)
	e := b.Evidence.Snapshot(b.Evidence.Generation(), w.ModelName)
	v.Verified = e.VerifiedInputTokens
	v.Ratio = e.TokenRatio
	v.InputSource = b.InputSource(ctx, name)
	return
}
