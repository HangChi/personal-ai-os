package knowledge

import (
	"context"
	"encoding/json"
	"slices"
	"strconv"
	"strings"
	"time"
)

const citationAuditRules = "核对回答中最多12条独立事实。claim 必须是回答中的连续原文，包含该事实所用的 [n]。只核对给出的证据。逐条标记 SUPPORTED（直接支持）、PARTIAL（部分支持）、UNSUPPORTED（证据不足或缺失）。SUPPORTED 和 PARTIAL 必须给出 claim 中的引用编号 index 和对应证据中至少四字符的连续原文 quote。UNSUPPORTED 的 index 为 0、quote 为空。仅返回 {\"claims\":[{\"claim\":\"回答原文\",\"status\":\"SUPPORTED\",\"index\":1,\"quote\":\"证据原文\"}]}。"

type CitationCheck struct {
	Claim  string `json:"claim"`
	Status string `json:"status"`
	Index  int    `json:"index"`
	Quote  string `json:"quote"`
}
type CitationAudit struct {
	Status string          `json:"status"`
	Claims []CitationCheck `json:"claims"`
}

func auditCitations(ctx context.Context, verifier StructuredModel, answer string, citations []Citation) *CitationAudit {
	unverified := &CitationAudit{Status: "UNVERIFIED", Claims: []CitationCheck{}}
	if len([]rune(answer)) > 6000 || strip(answer) == "" {
		return unverified
	}
	evidence := make(map[int]string, len(citations))
	for _, c := range citations {
		text := []rune(c.Content)
		evidence[c.Index] = string(text[:min(len(text), 1600)])
	}
	raw, err := verifier.Call(ctx, citationAuditRules, map[string]any{"answer": answer, "evidence": evidence}, 5*time.Second)
	var wire struct {
		Claims []struct {
			Claim, Status string
			Index         *int
			Quote         *string
		}
	}
	if err != nil || json.Unmarshal(raw, &wire) != nil || len(wire.Claims) == 0 || len(wire.Claims) > 12 {
		return unverified
	}
	result := CitationAudit{Status: "CHECKED", Claims: make([]CitationCheck, 0, len(wire.Claims))}
	seen := []string{}
	for _, row := range wire.Claims {
		if row.Index == nil || row.Quote == nil {
			return unverified
		}
		check := CitationCheck{Claim: row.Claim, Status: row.Status, Index: *row.Index, Quote: *row.Quote}
		if strip(check.Claim) == "" || !strings.Contains(answer, check.Claim) || slices.Contains(seen, check.Claim) {
			return unverified
		}
		seen = append(seen, check.Claim)
		switch check.Status {
		case "UNSUPPORTED":
			if check.Index != 0 || check.Quote != "" {
				return unverified
			}
		case "SUPPORTED", "PARTIAL":
			matched := false
			for _, mark := range citationMark.FindAllStringSubmatch(check.Claim, -1) {
				if index, err := strconv.Atoi(mark[1]); err == nil && index == check.Index {
					matched = true
					break
				}
			}
			if !matched || check.Index < 1 || len([]rune(strip(check.Quote))) < 4 || !strings.Contains(evidence[check.Index], check.Quote) {
				return unverified
			}
		default:
			return unverified
		}
		result.Claims = append(result.Claims, check)
	}
	return &result
}
