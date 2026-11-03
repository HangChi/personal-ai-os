package model

import (
	"context"
	"fmt"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/platform/api"
)

// SaveCommand 是整体覆盖表单；APIKey nil 保留、空白清除、其余替换。
type SaveCommand struct {
	BaseURL, APIKey, ChatModel, EmbeddingModel *string
	Temperature                                *Temperature
}

func (SaveCommand) String() string               { return "SaveModelCommand{redacted}" }
func (s SaveCommand) GoString() string           { return s.String() }
func (SaveCommand) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

type ProfileCommand struct {
	SaveCommand
	Name, PresetID, Protocol                 *string
	ChatModels, EmbeddingModels, AudioModels []*string
	ContextWindows                           map[string]*int
}

func (ProfileCommand) String() string               { return "SaveProfileCommand{redacted}" }
func (s ProfileCommand) GoString() string           { return s.String() }
func (ProfileCommand) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

type Store interface {
	ReadStore
	Require(context.Context, string) (Profile, error)
	Save(context.Context, SaveCommand) (Values, error)
	Create(context.Context, ProfileCommand) (Profile, error)
	Update(context.Context, string, ProfileCommand) (Profile, error)
	Activate(context.Context, string) (Profile, error)
	Delete(context.Context, string) error
	Reorder(context.Context, []string) error
	Rename(context.Context, string, string) (Profile, error)
}

type Service struct {
	*ReadService
	store   Store
	runtime *Runtime
}

func NewService(store Store, resolver *Resolver, runtime *Runtime) (*Service, error) {
	read, err := NewReadService(store, resolver)
	if err != nil {
		return nil, err
	}
	return &Service{read, store, runtime}, nil
}
func validation(message string) error { return api.NewBusinessError(api.ValidationFailed, message) }
func missing(id string) error {
	return api.NewBusinessError(api.ResourceNotFound, "模型配置不存在："+id)
}
func checkLength(value *string, max int, label string) error {
	if value != nil && len(utf16.Encode([]rune(*value))) > max {
		return validation(fmt.Sprintf("%s长度不能超过 %d 个字符", label, max))
	}
	return nil
}
func validateSaveLengths(c SaveCommand) error {
	for _, v := range []struct {
		p *string
		n int
		s string
	}{{c.BaseURL, 500, "端点地址"}, {c.APIKey, 500, "API Key"}, {c.ChatModel, 120, "对话模型名"}, {c.EmbeddingModel, 120, "嵌入模型名"}} {
		if err := checkLength(v.p, v.n, v.s); err != nil {
			return err
		}
	}
	return nil
}
func validateSaveValues(c SaveCommand) error {
	if c.Temperature != nil && (*c.Temperature < 0 || *c.Temperature > 2) {
		return validation("温度必须在 0.0 到 2.0 之间")
	}
	if c.BaseURL != nil && strip(*c.BaseURL) != "" && !httpURL(strip(*c.BaseURL)) {
		return validation("端点地址必须以 http:// 或 https:// 开头")
	}
	return nil
}
func validateSave(c SaveCommand) error {
	if err := validateSaveLengths(c); err != nil {
		return err
	}
	return validateSaveValues(c)
}
func validateProfile(c ProfileCommand) error {
	if err := validateSaveLengths(c.SaveCommand); err != nil {
		return err
	}
	for _, v := range []struct {
		p *string
		n int
		s string
	}{{c.PresetID, 60, "预设标识"}, {c.Protocol, 20, "协议类型"}} {
		if err := checkLength(v.p, v.n, v.s); err != nil {
			return err
		}
	}
	for i, list := range [][]*string{c.ChatModels, c.EmbeddingModels, c.AudioModels} {
		label := []string{"对话模型", "嵌入模型", "语音模型"}[i]
		if len(list) > 500 {
			return validation(label + "最多保存 500 个")
		}
		for _, s := range list {
			if err := checkLength(s, 120, label+"名"); err != nil {
				return err
			}
		}
	}
	if err := validateSaveValues(c.SaveCommand); err != nil {
		return err
	}
	if c.Name == nil || strip(*c.Name) == "" {
		return validation("配置名称不能为空")
	}
	return checkLength(blankToNull(c.Name), 60, "配置名称")
}
func (s *Service) Save(ctx context.Context, c SaveCommand) (ConfigDTO, error) {
	if err := validateSave(c); err != nil {
		return ConfigDTO{}, err
	}
	v, err := s.store.Save(ctx, c)
	if err != nil {
		return ConfigDTO{}, err
	}
	resolved := s.resolver.Resolve(v)
	// 仓储返回时事务已提交；应用失败不能撤销配置。
	if s.runtime != nil {
		if err = s.runtime.Apply(resolved); err != nil {
			return ConfigDTO{}, err
		}
	}
	return resolved.DTO(), nil
}
func (s *Service) Create(ctx context.Context, c ProfileCommand) (ProfileDTO, error) {
	if err := validateProfile(c); err != nil {
		return ProfileDTO{}, err
	}
	p, err := s.store.Create(ctx, c)
	if err != nil {
		return ProfileDTO{}, err
	}
	return s.resolver.ProfileDTO(p), nil
}
func (s *Service) Update(ctx context.Context, id string, c ProfileCommand) (ProfileDTO, error) {
	if err := validateProfile(c); err != nil {
		return ProfileDTO{}, err
	}
	p, err := s.store.Update(ctx, id, c)
	if err != nil {
		return ProfileDTO{}, err
	}
	if p.Active {
		if err = s.ApplyCurrent(ctx); err != nil {
			return ProfileDTO{}, err
		}
	}
	p, err = s.store.Require(ctx, id)
	if err != nil {
		return ProfileDTO{}, err
	}
	return s.resolver.ProfileDTO(p), nil
}
func (s *Service) Activate(ctx context.Context, id string) (ProfileDTO, error) {
	_, err := s.store.Activate(ctx, id)
	if err != nil {
		return ProfileDTO{}, err
	}
	if err = s.ApplyCurrent(ctx); err != nil {
		return ProfileDTO{}, err
	}
	p, err := s.store.Require(ctx, id)
	if err != nil {
		return ProfileDTO{}, err
	}
	return s.resolver.ProfileDTO(p), nil
}
func (s *Service) Rename(ctx context.Context, id, name string) (ProfileDTO, error) {
	name = strip(name)
	if name == "" {
		return ProfileDTO{}, validation("配置名称不能为空")
	}
	if err := checkLength(&name, 60, "配置名称"); err != nil {
		return ProfileDTO{}, err
	}
	p, err := s.store.Rename(ctx, id, name)
	if err != nil {
		return ProfileDTO{}, err
	}
	return s.resolver.ProfileDTO(p), nil
}
func (s *Service) Delete(ctx context.Context, id string) error { return s.store.Delete(ctx, id) }
func (s *Service) Reorder(ctx context.Context, ids []string) (ProfileListDTO, error) {
	if err := s.store.Reorder(ctx, ids); err != nil {
		return ProfileListDTO{}, err
	}
	return s.Profiles(ctx)
}
func (s *Service) ApplyCurrent(ctx context.Context) error {
	if s.runtime == nil {
		return nil
	}
	v, err := s.Current(ctx)
	if err != nil {
		return err
	}
	return s.runtime.Apply(v)
}

// Startup 忠实保留 Java 旧单行启动覆盖；active 用于后续按名解析与显式应用。
func (s *Service) Startup(ctx context.Context) error {
	v, found, err := s.store.Legacy(ctx)
	if err != nil {
		return err
	}
	if !found || v.Revision == nil || s.runtime == nil {
		return nil
	}
	return s.runtime.Apply(s.resolver.Resolve(v))
}
func (s *Service) ResolveNamed(ctx context.Context, name string) (*ChatClient, error) {
	name = strip(name)
	if name == "" {
		return nil, validation("模型名不能为空")
	}
	v, err := s.Current(ctx)
	if err != nil {
		return nil, err
	}
	if !v.HasAPIKey() {
		return nil, api.NewBusinessError(api.AINotConfigured, "当前配置没有可用的 API Key，无法按模型名切换")
	}
	v.ChatModel = name
	return s.runtime.factory(v)
}

// LookupWindow 供下一阶段预算器注册，故障仅回落，不能阻断聊天。
func (s *Service) LookupWindow(ctx context.Context, name string) *int {
	ps, err := s.store.Profiles(ctx)
	if err != nil {
		return nil
	}
	for _, p := range ps {
		if !p.Active {
			continue
		}
		for _, m := range p.Models {
			if m.Kind == Chat && m.Name == name && m.ContextWindow != nil && *m.ContextWindow >= 1024 {
				return copyPointer(m.ContextWindow)
			}
		}
	}
	return nil
}

func firstModel(list []*string, fallback *string) *string {
	for _, p := range list {
		if v := blankToNull(p); v != nil {
			return v
		}
	}
	return blankToNull(fallback)
}
