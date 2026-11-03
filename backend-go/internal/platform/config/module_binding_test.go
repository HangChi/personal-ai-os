package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func boundRemainingRecord(kind string, input map[string]string) (any, error) {
	var value any
	var err error
	switch kind {
	case "plan":
		value, err = BindPlanProperties(input)
	case "calendar":
		value, err = BindCalendarProperties(input)
	case "ai":
		var properties AIProperties
		properties, err = BindAIProperties(input)
		value = struct {
			Memory AIMemory `json:"memory"`
			Prompt string   `json:"systemPrompt"`
		}{properties.Memory, properties.SystemPrompt()}
	case "context":
		value, err = BindContextProperties(input)
	case "protection":
		value, err = BindContextProtectionProperties(input)
	case "app":
		var properties AppProperties
		properties, err = BindAppProperties(input)
		value = struct {
			Name    *string `json:"name"`
			Version *string `json:"version"`
		}{properties.Name(), properties.Version()}
	case "knowledge":
		var properties KnowledgeProperties
		properties, err = BindKnowledgeProperties(input)
		fields := normalizedRecord(reflect.ValueOf(properties)).(map[string]any)
		// 非有限Double被原record保留，不能通过JSON编码失败悄悄丢掉。
		fields["similarityThreshold"] = normalizedRecord(reflect.ValueOf(properties.SimilarityThreshold))
		fields["keywordChannelWeight"] = normalizedRecord(reflect.ValueOf(properties.KeywordChannelWeight))
		fields["allowedExtensions"] = properties.AllowedExtensions()
		value = fields
	case "memory":
		value, err = BindMemoryProperties(input)
	default:
		return nil, errors.New("未知配置夹具")
	}
	if err != nil {
		return nil, err
	}
	return normalizedRecord(reflect.ValueOf(value)), nil
}

var durationType = reflect.TypeFor[time.Duration]()

// 仅测试使用：按Java record字段名和精确时长输出，生产配置JSON保持脱敏。
func normalizedRecord(value reflect.Value) any {
	if !value.IsValid() {
		return nil
	}
	if value.Kind() == reflect.Interface || value.Kind() == reflect.Pointer {
		if value.IsNil() {
			return nil
		}
		return normalizedRecord(value.Elem())
	}
	if value.Type() == durationType {
		return strconv.FormatInt(value.Int(), 10)
	}
	switch value.Kind() {
	case reflect.Struct:
		result := map[string]any{}
		for index := 0; index < value.NumField(); index++ {
			field := value.Type().Field(index)
			if !field.IsExported() {
				continue
			}
			key := strings.Split(field.Tag.Get("json"), ",")[0]
			if key == "-" {
				continue
			}
			if key == "" {
				key = strings.ToLower(field.Name[:1]) + field.Name[1:]
			}
			result[key] = normalizedRecord(value.Field(index))
		}
		return result
	case reflect.Float64:
		v := value.Float()
		if math.IsNaN(v) {
			return "NaN"
		}
		if math.IsInf(v, 1) {
			return "Infinity"
		}
		if math.IsInf(v, -1) {
			return "-Infinity"
		}
		return v
	}
	return value.Interface()
}

func TestModuleConfigurationDefaultsAndHelpers(t *testing.T) {
	calendar, err := BindCalendarProperties(nil)
	if err != nil {
		t.Fatal(err)
	}
	if calendar.ScanInitialDelayMS != 0 || calendar.EffectiveScanLookahead() != 7*24*time.Hour {
		t.Fatal("原始primitive零值/有效前瞻窗口未保留")
	}
	for _, requested := range []*int{nil, new(0), new(-1), new(1), new(1000)} {
		wantDays, wantLimit := 7, 100
		if requested != nil && *requested > 0 {
			wantDays = min(*requested, 90)
			wantLimit = min(*requested, 500)
		}
		if calendar.NormalizeRangeDays(requested) != wantDays || calendar.NormalizeLimit(requested) != wantLimit {
			t.Fatal("查询归一化不一致")
		}
	}
	// 不将静态defaults()工厂的maxRetries=1冒充Binder的primitive缺省0。
	protection, err := BindContextProtectionProperties(nil)
	if err != nil || protection.MaxRetries != 0 {
		t.Fatal("绑定缺省不等于工厂缺省", err)
	}
	if DefaultContextProtectionProperties().MaxRetries != 1 {
		t.Fatal("工厂默认值错误")
	}
	knowledge, err := BindKnowledgeProperties(nil)
	if err != nil || knowledge.SimilarityThreshold != 0 || knowledge.NormalizeTopK(nil) != 4 || knowledge.NormalizeTopK(new(100)) != 20 {
		t.Fatal("原始double零值和上限未保留", err)
	}
	if !knowledge.IsExtensionAllowed(" MD ") || knowledge.IsExtensionAllowed("\u00a0md") || knowledge.IsExtensionAllowed("") {
		t.Fatal("扩展名规则不一致")
	}
	memory, err := BindMemoryProperties(nil)
	if err != nil || !memory.Ranking.Enabled || memory.Ranking.HalfLifeDays != 0 || !memory.Backfill.Enabled {
		t.Fatal("嵌套空record默认值错误", err)
	}
	context, err := BindContextProperties(map[string]string{"app.context.summary.min-messages": "8"})
	if err != nil || context.Summary.Enabled {
		t.Fatal("已绑定nested的primitive布尔值应为false", err)
	}
}

func TestModuleConfigurationCopiesAndRedaction(t *testing.T) {
	input := map[string]string{"app.ai.system-prompt": "fixture-private-prompt", "app.name": "fixture-private-name", "app.version": "fixture-private-version", "app.knowledge.allowed-extensions": "MD,txt"}
	ai, err := BindAIProperties(input)
	if err != nil {
		t.Fatal(err)
	}
	app, err := BindAppProperties(input)
	if err != nil {
		t.Fatal(err)
	}
	knowledge, err := BindKnowledgeProperties(input)
	if err != nil {
		t.Fatal(err)
	}
	*app.Name() = "mutated"
	*app.Version() = "mutated"
	knowledge.AllowedExtensions()[0] = "mutated"
	input["app.ai.system-prompt"] = "mutated"
	if ai.SystemPrompt() != "fixture-private-prompt" || *app.Name() != "fixture-private-name" || *app.Version() != "fixture-private-version" || knowledge.AllowedExtensions()[0] != "MD" {
		t.Fatal("可变配置别名泄漏")
	}
	for _, value := range []any{ai, &ai, app, &app} {
		for _, format := range []string{"%v", "%+v", "%#v"} {
			if strings.Contains(fmt.Sprintf(format, value), "fixture-private") {
				t.Fatal("格式化泄漏配置")
			}
		}
		encoded, err := json.Marshal(value)
		if err != nil || strings.Contains(string(encoded), "fixture-private") {
			t.Fatal("JSON泄漏配置")
		}
	}
	for kind, input := range map[string]map[string]string{
		"ai": {"app.ai.system-prompt": string([]byte{0xff})}, "app": {"app.name": string([]byte{0xff})},
		"knowledge": {"app.knowledge.allowed-extensions": string([]byte{0xff})},
		"memory":    {"app.memory.similar-threshold": "fixture-private-invalid"},
	} {
		if _, err := boundRemainingRecord(kind, input); err == nil || strings.Contains(err.Error(), "fixture-private") {
			t.Fatal("非法值未脱敏拒绝", kind)
		}
	}
	var workers sync.WaitGroup
	for range 100 {
		workers.Go(func() {
			for range 10 {
				properties, err := BindKnowledgeProperties(input)
				if err != nil || properties.AllowedExtensions()[0] != "MD" {
					t.Error("并发配置串扰", err)
					return
				}
				properties.AllowedExtensions()[0] = "mutated"
			}
		})
	}
	workers.Wait()
}
