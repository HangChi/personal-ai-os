package chat

import (
	"context"
	"errors"
)

// Retrieval failures are recoverable. Keep warning distinct from terminal error
// so durable runs retain their lease and persist the subsequent answer.
func (e *Engine) retrieveForPrompt(ctx context.Context, content string, topK int, publish Publish) (RetrievedEvidence, error) {
	var evidence RetrievedEvidence
	var err error
	switch {
	case e.RetrieveEvidence != nil:
		evidence, err = e.RetrieveEvidence(ctx, content, topK)
	case e.Retrieve != nil:
		evidence.Citations, err = e.Retrieve(ctx, content, topK)
	default:
		err = errors.New("knowledge retrieval port unavailable")
	}
	if ctx.Err() != nil {
		return RetrievedEvidence{}, ctx.Err()
	}
	if err == nil {
		return evidence, nil
	}
	if e.Logger != nil {
		e.Logger.WarnContext(ctx, "知识检索失败，本轮退回普通对话")
	}
	return RetrievedEvidence{}, publish("warning", map[string]string{
		"code":    "KNOWLEDGE_RETRIEVAL_FAILED",
		"message": "知识库检索失败，本轮未使用知识库回答",
	})
}
