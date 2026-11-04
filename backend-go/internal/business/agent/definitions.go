package agent

import (
	_ "embed"
	"encoding/json"
	"personal-ai-os/backend-go/internal/business/model"
)

//go:embed tool-definitions.json
var javaToolDefinitions []byte

//go:embed tool-definitions-p5.json
var javaP5ToolDefinitions []byte

// 固定基线 Spring ToolCallbacks 直接生成；更新必须重跑 Java oracle。
func builtinDefinition(name string) (model.Tool, bool) {
	for _, source := range [][]byte{javaToolDefinitions, javaP5ToolDefinitions} {
		var definitions []model.Tool
		if json.Unmarshal(source, &definitions) != nil {
			panic("内置工具元数据损坏")
		}
		for _, definition := range definitions {
			if definition.Name == name {
				return definition, true
			}
		}
	}
	return model.Tool{}, false
}
