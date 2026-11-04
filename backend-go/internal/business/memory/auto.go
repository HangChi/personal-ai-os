package memory

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/business/model"
)

const captureSystem = "你负责判断这段对话里有哪些内容值得**长期**记住。\n" +
	"只保留：用户明确表达的长期偏好、身份或环境事实、反复出现的约束、约定好的规则。\n" +
	"不要保留：一次性的任务内容、临时的方案、对话过程、任何需要推测的内容。\n" +
	"只输出 JSON 字符串数组，不含任何解释；没有值得记住的内容时输出 []。\n"

func (s *Store) Capture(ctx context.Context, runtime *model.Runtime, user, reply string, limit int) error {
	if strip(user) == "" || strip(reply) == "" || limit <= 0 {
		return nil
	}
	client, _, err := runtime.TargetSnapshot(ctx, "")
	if err != nil {
		return err
	}
	result, err := client.Call(ctx, model.ChatRequest{Messages: []model.Message{
		{Role: "system", Content: captureSystem},
		{Role: "user", Content: "用户：" + user + "\n助手：" + reply},
	}})
	if err != nil {
		return err
	}
	for _, fact := range parseFacts(result.Content, limit) {
		_, saveErr := s.CreateAuto(ctx, fact)
		err = errors.Join(err, saveErr)
	}
	return err
}

func parseFacts(raw string, limit int) []string {
	start, end := strings.IndexByte(raw, '['), strings.LastIndexByte(raw, ']')
	if start < 0 || end <= start || limit <= 0 {
		return nil
	}
	var values []string
	if err := json.Unmarshal([]byte(raw[start:end+1]), &values); err != nil {
		return nil
	}
	var facts []string
	for _, value := range values {
		value = strip(value)
		length := len(utf16.Encode([]rune(value)))
		if length >= 5 && length <= 2000 {
			facts = append(facts, value)
		}
		if len(facts) == limit {
			break
		}
	}
	return facts
}
