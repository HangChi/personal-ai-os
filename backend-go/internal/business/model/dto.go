package model

import "time"

type ConfigDTO struct {
	BaseURL                string            `json:"baseUrl"`
	APIKeyMasked           string            `json:"apiKeyMasked"`
	HasAPIKey              bool              `json:"hasApiKey"`
	ChatModel              string            `json:"chatModel"`
	Temperature            *Temperature      `json:"temperature"`
	EmbeddingModel         string            `json:"embeddingModel"`
	Revision               *int              `json:"revision"`
	UpdatedAt              *time.Time        `json:"updatedAt"`
	Sources                map[string]Source `json:"sources"`
	HotReloadable          bool              `json:"hotReloadable"`
	EmbeddingHotReloadable bool              `json:"embeddingHotReloadable"`
}

type Kind string

const (
	Chat      Kind = "CHAT"
	Embedding Kind = "EMBEDDING"
	Audio     Kind = "AUDIO"
)

type Model struct {
	ID            int64
	ProfileID     string
	Kind          Kind
	Name          string
	SortOrder     int
	ContextWindow *int
	CreatedAt     time.Time
}

type Profile struct {
	Values
	ID, Name, Protocol string
	PresetID           *string
	Active             bool
	SortOrder          int
	CreatedAt          time.Time
	Models             []Model
}

type ProfileDTO struct {
	ID              string            `json:"id"`
	Name            string            `json:"name"`
	PresetID        *string           `json:"presetId"`
	BaseURL         string            `json:"baseUrl"`
	Protocol        string            `json:"protocol"`
	APIKeyMasked    string            `json:"apiKeyMasked"`
	HasAPIKey       bool              `json:"hasApiKey"`
	ChatModel       string            `json:"chatModel"`
	Temperature     *Temperature      `json:"temperature"`
	EmbeddingModel  string            `json:"embeddingModel"`
	ChatModels      []string          `json:"chatModels"`
	EmbeddingModels []string          `json:"embeddingModels"`
	AudioModels     []string          `json:"audioModels"`
	ContextWindows  map[string]int    `json:"contextWindows"`
	AudioModel      string            `json:"audioModel"`
	Active          bool              `json:"active"`
	SortOrder       int               `json:"sortOrder"`
	Revision        int               `json:"revision"`
	UpdatedAt       *time.Time        `json:"updatedAt"`
	Sources         map[string]Source `json:"sources"`
}

type ProfileListDTO struct {
	Profiles []ProfileDTO `json:"profiles"`
	ActiveID *string      `json:"activeId"`
}

func (r *Resolver) ProfileDTO(profile Profile) ProfileDTO {
	resolved := r.Resolve(profile.Values)
	d := ProfileDTO{ID: profile.ID, Name: profile.Name, PresetID: copyPointer(profile.PresetID), BaseURL: resolved.BaseURL,
		Protocol: profile.Protocol, APIKeyMasked: Mask(resolved.apiKey), HasAPIKey: resolved.HasAPIKey(), ChatModel: resolved.ChatModel,
		Temperature: resolved.Temperature, EmbeddingModel: resolved.EmbeddingModel, ChatModels: []string{}, EmbeddingModels: []string{}, AudioModels: []string{},
		ContextWindows: map[string]int{}, Active: profile.Active, SortOrder: profile.SortOrder, UpdatedAt: resolved.UpdatedAt, Sources: resolved.Sources}
	if resolved.Revision != nil {
		d.Revision = *resolved.Revision
	}
	if resolved.AudioModel != nil {
		d.AudioModel = *resolved.AudioModel
	}
	models := profile.Models
	// 原服务仅在整个子表为空时回落，且历史兜底只合成 CHAT/EMBEDDING。
	if len(models) == 0 {
		for _, field := range []struct {
			kind  Kind
			value *string
		}{{Chat, profile.ChatModel}, {Embedding, profile.EmbeddingModel}} {
			if name := blankToNull(field.value); name != nil {
				models = append(models, Model{Kind: field.kind, Name: *name})
			}
		}
	}
	for _, m := range models {
		switch m.Kind {
		case Embedding:
			d.EmbeddingModels = append(d.EmbeddingModels, m.Name)
		case Audio:
			d.AudioModels = append(d.AudioModels, m.Name)
		default:
			d.ChatModels = append(d.ChatModels, m.Name)
			if m.ContextWindow != nil {
				d.ContextWindows[m.Name] = *m.ContextWindow
			}
		}
	}
	return d
}
