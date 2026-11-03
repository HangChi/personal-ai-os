package model

import (
	"context"
	"errors"
)

// ReadStore 返回数据库意图值，密钥已经过 KeyCodec 解密。Current 在同一快照中
// 优先取 active 档案，再取 V20 单行；不能把未激活档案当作当前配置。
type ReadStore interface {
	Legacy(context.Context) (Values, bool, error)
	Current(context.Context) (Values, error)
	Profiles(context.Context) ([]Profile, error)
}

type ReadService struct {
	store    ReadStore
	resolver *Resolver
}

func NewReadService(store ReadStore, resolver *Resolver) (*ReadService, error) {
	if store == nil || resolver == nil {
		return nil, errors.New("模型读取服务需要仓储与配置解析器")
	}
	return &ReadService{store: store, resolver: resolver}, nil
}

// Config 保留 /settings/model 旧入口只读取单行表的语义。
func (s *ReadService) Config(ctx context.Context) (ConfigDTO, error) {
	values, _, err := s.store.Legacy(ctx)
	if err != nil {
		return ConfigDTO{}, err
	}
	return s.resolver.Resolve(values).DTO(), nil
}

// Current 供未来模型/音频适配器使用，不缓存凭据；切换档案后重新读取。
func (s *ReadService) Current(ctx context.Context) (Resolved, error) {
	values, err := s.store.Current(ctx)
	if err != nil {
		return Resolved{}, err
	}
	return s.resolver.Resolve(values), nil
}

func (s *ReadService) Profiles(ctx context.Context) (ProfileListDTO, error) {
	profiles, err := s.store.Profiles(ctx)
	if err != nil {
		return ProfileListDTO{}, err
	}
	out := ProfileListDTO{Profiles: make([]ProfileDTO, 0, len(profiles))}
	for _, p := range profiles {
		out.Profiles = append(out.Profiles, s.resolver.ProfileDTO(p))
		if p.Active && out.ActiveID == nil {
			out.ActiveID = copyPointer(&p.ID)
		}
	}
	return out, nil
}
