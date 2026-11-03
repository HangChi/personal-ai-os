// Package confirmation manages human approval independently of business actions.
package confirmation

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

type Action string

const (
	DeleteTask          Action = "DELETE_TASK"
	ScheduleTask        Action = "SCHEDULE_TASK"
	SetPriority         Action = "SET_PRIORITY"
	DeleteCalendarEvent Action = "DELETE_CALENDAR_EVENT"
	MCPToolCall         Action = "MCP_TOOL_CALL"
)

var allActions = []Action{DeleteTask, ScheduleTask, SetPriority, DeleteCalendarEvent, MCPToolCall}

type Status string

const (
	Pending   Status = "PENDING"
	Confirmed Status = "CONFIRMED"
	Cancelled Status = "CANCELLED"
	Expired   Status = "EXPIRED"
	Failed    Status = "FAILED"
)
const Validity = 10 * time.Minute

type Record struct {
	ID, Token, ConversationID, Summary, Payload string
	Action                                      Action
	AffectedCount                               int
	Status                                      Status
	CreatedAt, ExpiresAt                        time.Time
	ResolvedAt                                  *time.Time
}
type DTO struct {
	ID            string    `json:"id"`
	Token         string    `json:"token"`
	Action        Action    `json:"action"`
	Summary       string    `json:"summary"`
	AffectedCount int       `json:"affectedCount"`
	Status        Status    `json:"status"`
	ExpiresAt     time.Time `json:"expiresAt"`
	CreatedAt     time.Time `json:"createdAt"`
}

func (r Record) DTO() DTO {
	return DTO{r.ID, r.Token, r.Action, r.Summary, r.AffectedCount, r.Status, r.ExpiresAt, r.CreatedAt}
}

type Execution struct {
	Executed      bool   `json:"executed"`
	AffectedCount int    `json:"affectedCount"`
	Summary       string `json:"summary"`
}
type Handler interface {
	Actions() []Action
	Execute(context.Context, Action, string) (int, error)
}
type Registry struct{ handlers map[Action]Handler }

func NewRegistry(handlers ...Handler) (*Registry, error) {
	return NewRegistryFor(allActions, handlers...)
}

// NewRegistryFor validates the actions assembled in the current migration stage.
// The full registry remains strict once calendar and MCP handlers are installed.
func NewRegistryFor(required []Action, handlers ...Handler) (*Registry, error) {
	m := make(map[Action]Handler)
	for _, h := range handlers {
		if h == nil {
			return nil, errors.New("确认处理器不能为空")
		}
		for _, a := range h.Actions() {
			if _, ok := m[a]; ok {
				return nil, fmt.Errorf("待确认动作 %s 被多个处理器声明", a)
			}
			m[a] = h
		}
	}
	for _, a := range required {
		if m[a] == nil {
			return nil, fmt.Errorf("待确认动作缺少处理器：%s", a)
		}
	}
	return &Registry{m}, nil
}
func (r *Registry) Require(a Action) (Handler, error) {
	if r == nil || r.handlers[a] == nil {
		return nil, fmt.Errorf("待确认动作没有处理器：%s", a)
	}
	return r.handlers[a], nil
}

type Store interface {
	Create(context.Context, Record) (Record, error)
	Get(context.Context, string) (Record, bool, error)
	List(context.Context, string, Status) ([]Record, error)
	CompareAndSet(context.Context, string, Status, Status, time.Time) (bool, error)
}
type Error struct {
	Code    api.ErrorCode
	Message string
}

func (e *Error) Error() string      { return e.Message }
func inactive(message string) error { return &Error{api.ConfirmationNotActive, message} }
func missing(id string) error {
	return &Error{api.ResourceNotFound, "待确认操作不存在：" + id}
}

// ErrTargetMissing is returned by a handler when its target disappeared.
var ErrTargetMissing = errors.New("待确认目标不存在")

type Service struct {
	store    Store
	registry *Registry
	now      func() time.Time
}

func NewService(store Store, registry *Registry) (*Service, error) {
	if store == nil || registry == nil {
		return nil, errors.New("确认仓储与处理器注册表不能为空")
	}
	return &Service{store, registry, time.Now}, nil
}
func (s *Service) Create(ctx context.Context, a Action, summary string, payload any, count int, conversation string) (DTO, error) {
	if _, err := s.registry.Require(a); err != nil {
		return DTO{}, err
	}
	if summary == "" || count < 0 {
		return DTO{}, errors.New("确认描述或影响数无效")
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return DTO{}, err
	}
	token := make([]byte, 24)
	if _, err = rand.Read(token); err != nil {
		return DTO{}, err
	}
	now := s.now().UTC()
	r := Record{Token: hex.EncodeToString(token), ConversationID: conversation, Action: a, Summary: summary, Payload: string(data), AffectedCount: count, Status: Pending, CreatedAt: now, ExpiresAt: now.Add(Validity)}
	r, err = s.store.Create(ctx, r)
	return r.DTO(), err
}
func (s *Service) List(ctx context.Context, conversation string, status Status) ([]DTO, error) {
	if status == "" {
		status = Pending
	}
	found, err := s.store.List(ctx, strings.TrimSpace(conversation), status)
	if err != nil {
		return nil, err
	}
	result := make([]DTO, 0, len(found))
	for _, r := range found {
		result = append(result, r.DTO())
	}
	return result, nil
}
func (s *Service) require(ctx context.Context, id, token string) (Record, error) {
	r, ok, err := s.store.Get(ctx, id)
	if err != nil {
		return Record{}, err
	}
	if !ok {
		return Record{}, missing(id)
	}
	if token == "" || r.Token != strings.TrimSpace(token) {
		return Record{}, inactive("确认令牌不匹配")
	}
	return r, nil
}
func (s *Service) Confirm(ctx context.Context, id, token string) (Execution, error) {
	r, err := s.require(ctx, id, token)
	if err != nil {
		return Execution{}, err
	}
	if r.Status != Pending {
		return Execution{}, inactive("该确认已失效或已被处理")
	}
	now := s.now().UTC()
	if r.ExpiresAt.Before(now) {
		if _, err = s.store.CompareAndSet(ctx, id, Pending, Expired, now); err != nil {
			return Execution{}, err
		}
		return Execution{}, inactive("该确认已过期，请重新发起")
	}
	claimed, err := s.store.CompareAndSet(ctx, id, Pending, Confirmed, now)
	if err != nil {
		return Execution{}, err
	}
	if !claimed {
		return Execution{}, inactive("该确认已被处理")
	}
	handler, err := s.registry.Require(r.Action)
	if err != nil {
		return Execution{}, err
	}
	count, runErr := handler.Execute(ctx, r.Action, r.Payload)
	outcome := Confirmed
	if errors.Is(runErr, ErrTargetMissing) {
		outcome = Cancelled
	} else if runErr != nil {
		outcome = Failed
	}
	updated, saveErr := s.store.CompareAndSet(context.WithoutCancel(ctx), id, Confirmed, outcome, s.now().UTC())
	if saveErr != nil {
		return Execution{}, errors.Join(runErr, saveErr)
	}
	if !updated {
		return Execution{}, errors.Join(runErr, errors.New("确认结果回写未命中"))
	}
	if errors.Is(runErr, ErrTargetMissing) {
		return Execution{false, 0, "目标已不存在，未执行任何变更"}, nil
	}
	if runErr != nil {
		return Execution{}, runErr
	}
	return Execution{true, count, "已执行：" + r.Summary}, nil
}
func (s *Service) Cancel(ctx context.Context, id, token string) error {
	if _, err := s.require(ctx, id, token); err != nil {
		return err
	}
	updated, err := s.store.CompareAndSet(ctx, id, Pending, Cancelled, s.now().UTC())
	if err != nil {
		return err
	}
	if !updated {
		return inactive("该确认已失效或已被处理")
	}
	return nil
}
