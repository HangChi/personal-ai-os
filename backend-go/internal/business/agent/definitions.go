package agent

import (
	_ "embed"
	"encoding/json"
	"personal-ai-os/backend-go/internal/business/model"
)

//go:embed tool-definitions.json
var javaToolDefinitions []byte

// 固定基线 Spring ToolCallbacks 直接生成；更新必须重跑 Java oracle。
func builtinDefinition(name string) (model.Tool, bool) {
	var definitions []model.Tool
	if json.Unmarshal(javaToolDefinitions, &definitions) != nil {
		panic("内置工具元数据损坏")
	}
	for _, d := range definitions {
		if d.Name == name {
			return d, true
		}
	}
	return model.Tool{}, false
}
