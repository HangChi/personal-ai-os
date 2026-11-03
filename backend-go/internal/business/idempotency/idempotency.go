// Package idempotency implements the durable operation ownership used by HTTP
// writes and agent tools. A committed IN_PROGRESS row is the ownership token;
// callers must never retry an action after ownership has been acquired.
package idempotency

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"
	"unicode/utf8"
)

type Source string

const (
	SourceTool Source = "TOOL"
	SourceHTTP Source = "HTTP"
)

type Outcome string

const (
	InProgress           Outcome = "IN_PROGRESS"
	Success              Outcome = "SUCCESS"
	RequiresConfirmation Outcome = "REQUIRES_CONFIRMATION"
	Rejected             Outcome = "REJECTED"
	Duplicate            Outcome = "DUPLICATE"
	Failed               Outcome = "FAILED"
)

type Request struct {
	Key, Operation, Digest, ConversationID, TurnID string
	Source                                         Source
}

type Result struct {
	ResponsePayload *string
	Outcome         Outcome
	Replayed        bool
}

func NewResult(payload *string, outcome Outcome) Result {
	return Result{ResponsePayload: payload, Outcome: outcome}
}

type Record struct {
	ID                                             int64
	Key, Operation, Digest, ConversationID, TurnID string
	Source                                         Source
	Outcome                                        Outcome
	DurationMS                                     *int
	ResponsePayload                                *string
	CreatedAt                                      time.Time
}

// Store operations are each independent short transactions. Claim must atomically
// insert or return the winner; it must not leave a transaction aborted after a
// unique-key race.
type Store interface {
	Find(context.Context, string) (Record, bool, error)
	Claim(context.Context, Record) (winner Record, claimed bool, err error)
	DeleteStaleHTTP(context.Context, Record, time.Time) (bool, error)
	Complete(context.Context, string, Outcome, *string, int) error
}

type ErrorKind string

const (
	ErrDigestConflict ErrorKind = "digest_conflict"
	ErrInProgress     ErrorKind = "in_progress"
	ErrPreviousFailed ErrorKind = "previous_failed"
)

type ConflictError struct {
	Kind ErrorKind
	Key  string
}

func (e *ConflictError) Error() string {
	switch e.Kind {
	case ErrDigestConflict:
		return "该幂等键已用于内容不同的请求，请更换幂等键：" + e.Key
	case ErrPreviousFailed:
		return "该操作之前执行失败，请更换幂等键后重试：" + e.Key
	default:
		return "该操作的执行结果尚未确定，请核对后再继续，系统不会重复执行"
	}
}

type Service struct {
	store Store
	now   func() time.Time
}

const StaleAfter = 5 * time.Minute

func NewService(store Store) (*Service, error) {
	if store == nil {
		return nil, errors.New("幂等仓储不能为空")
	}
	return &Service{store: store, now: time.Now}, nil
}

func (s *Service) Execute(ctx context.Context, request Request, action func(context.Context) (Result, error)) (Result, error) {
	if err := validate(request, action); err != nil {
		return Result{}, err
	}
	for {
		existing, found, err := s.store.Find(ctx, request.Key)
		if err != nil {
			return Result{}, err
		}
		if found {
			if existing.Digest != request.Digest {
				return Result{}, &ConflictError{Kind: ErrDigestConflict, Key: request.Key}
			}
			if existing.Outcome == InProgress && existing.Source == SourceHTTP && existing.CreatedAt.Before(s.now().Add(-StaleAfter)) {
				deleted, deleteErr := s.store.DeleteStaleHTTP(ctx, existing, s.now().Add(-StaleAfter))
				if deleteErr != nil {
					return Result{}, deleteErr
				}
				if deleted {
					continue
				}
				continue // the row changed between read and conditional delete
			}
			return replay(existing, request)
		}

		placeholder := Record{Key: request.Key, Source: request.Source, Operation: request.Operation,
			Digest: request.Digest, ConversationID: request.ConversationID, TurnID: request.TurnID,
			Outcome: InProgress, CreatedAt: s.now().UTC()}
		winner, claimed, err := s.store.Claim(ctx, placeholder)
		if err != nil {
			return Result{}, err
		}
		if !claimed {
			return replay(winner, request)
		}

		started := time.Now()
		result, actionErr := action(ctx)
		duration := elapsedMillis(started)
		if actionErr != nil {
			completeErr := s.store.Complete(context.WithoutCancel(ctx), request.Key, Failed, nil, duration)
			return Result{}, errors.Join(actionErr, completeErr)
		}
		if err := finalOutcome(result.Outcome); err != nil {
			completeErr := s.store.Complete(context.WithoutCancel(ctx), request.Key, Failed, nil, duration)
			return Result{}, errors.Join(err, completeErr)
		}
		if err := s.store.Complete(context.WithoutCancel(ctx), request.Key, result.Outcome, result.ResponsePayload, duration); err != nil {
			// The action may already have committed. Never execute it again under this key.
			return Result{}, fmt.Errorf("幂等结果回填失败，操作结果不明: %w", err)
		}
		return result, nil
	}
}

func replay(record Record, request Request) (Result, error) {
	if record.Digest != request.Digest {
		return Result{}, &ConflictError{Kind: ErrDigestConflict, Key: request.Key}
	}
	switch record.Outcome {
	case InProgress:
		return Result{}, &ConflictError{Kind: ErrInProgress, Key: request.Key}
	case Failed:
		return Result{}, &ConflictError{Kind: ErrPreviousFailed, Key: request.Key}
	default:
		return Result{ResponsePayload: record.ResponsePayload, Outcome: record.Outcome, Replayed: true}, nil
	}
}

func validate(request Request, action func(context.Context) (Result, error)) error {
	if request.Key == "" || utf8.RuneCountInString(request.Key) > 160 || request.Operation == "" || utf8.RuneCountInString(request.Operation) > 120 || len(request.Digest) != 64 {
		return errors.New("幂等请求字段无效")
	}
	if request.Source != SourceHTTP && request.Source != SourceTool {
		return errors.New("幂等来源无效")
	}
	if _, err := hex.DecodeString(request.Digest); err != nil || action == nil {
		return errors.New("幂等摘要或动作无效")
	}
	return nil
}

func finalOutcome(outcome Outcome) error {
	switch outcome {
	case Success, RequiresConfirmation, Rejected, Failed:
		return nil
	default:
		return fmt.Errorf("动作返回了非法最终状态 %q", outcome)
	}
}

func elapsedMillis(start time.Time) int {
	value := time.Since(start) / time.Millisecond
	if value > time.Duration(^uint(0)>>1) {
		return int(^uint(0) >> 1)
	}
	return int(value)
}

// CanonicalJSON uses encoding/json's deterministic map-key ordering while
// disabling HTML escaping so digests describe JSON data rather than HTML.
func CanonicalJSON(value any) ([]byte, error) {
	var out bytes.Buffer
	encoder := json.NewEncoder(&out)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		return nil, fmt.Errorf("JSON 序列化失败: %w", err)
	}
	return bytes.TrimSuffix(out.Bytes(), []byte{'\n'}), nil
}

func Digest(value any) (string, error) {
	canonical, err := CanonicalJSON(value)
	if err != nil {
		return "", err
	}
	return SHA256(string(canonical)), nil
}

func SHA256(raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}

func ToolKey(conversationID, turnID, toolName, digest string) string {
	return SHA256(conversationID + "|" + turnID + "|" + toolName + "|" + digest)
}
