package memory

import (
	"context"
	"database/sql"
	"fmt"
	"math"
	"sort"
	"strings"
	"time"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/platform/config"
)

const promptHeader = "## 长期记忆\n\n以下是用户明确要求你长期记住的信息，在所有回答中都应遵守：\n"

func (s *Store) Prompt(ctx context.Context, user string, settings config.MemoryProperties) (string, error) {
	var candidates []Entry
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		rows, err := conn.QueryContext(ctx, "SELECT "+columns+" FROM memory WHERE superseded_by IS NULL ORDER BY updated_at DESC LIMIT 120")
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var entry Entry
			if err := scan(rows, &entry); err != nil {
				return err
			}
			candidates = append(candidates, entry)
		}
		return rows.Err()
	})
	if err != nil {
		return "", err
	}
	now := time.Now().UTC()
	block, used := render(candidates, user, settings, now)
	if len(used) != 0 {
		// Refresh by ID with a database-side cutoff so parallel requests cannot double count
		// or overwrite a concurrent edit.
		_ = s.pool.WithConn(ctx, func(conn *sql.Conn) error {
			_, err := conn.ExecContext(ctx, `UPDATE memory SET hit_count=hit_count+1,last_used_at=$2
				WHERE id=ANY($1::uuid[]) AND (last_used_at IS NULL OR last_used_at<=$3)`, used, now, now.Add(-10*time.Minute))
			return err
		})
	}
	return block, nil
}

func render(candidates []Entry, user string, settings config.MemoryProperties, now time.Time) (string, []string) {
	var pinned, rest []Entry
	for _, entry := range candidates {
		if entry.Pinned && len(pinned) < settings.PinnedMaxCount {
			pinned = append(pinned, entry)
		} else {
			rest = append(rest, entry)
		}
	}
	query := grams(user)
	scores := make(map[string]float64, len(rest))
	for _, entry := range rest {
		recency := 1.0
		if settings.Ranking.HalfLifeDays > 0 {
			days := math.Max(0, float64(now.Sub(entry.UpdatedAt)/time.Minute)/1440)
			recency = math.Pow(0.5, days/float64(settings.Ranking.HalfLifeDays))
		}
		var relevance float64
		if len(query) > 0 {
			memory := grams(entry.Content)
			var hits int
			for pair := range query {
				if _, ok := memory[pair]; ok {
					hits++
				}
			}
			relevance = float64(hits) / float64(len(query))
		}
		scores[entry.ID] = 0.7*relevance + 0.3*recency
	}
	sort.SliceStable(rest, func(i, j int) bool {
		return scores[rest[i].ID] > scores[rest[j].ID]
	})
	var block strings.Builder
	var ids []string
	var chars int
	for _, entry := range append(pinned, rest...) {
		chars += len(utf16.Encode([]rune(entry.Content)))
		if len(ids) == 30 || chars > 6000 {
			break
		}
		ids = append(ids, entry.ID)
		fmt.Fprintf(&block, "\n%d. %s", len(ids), entry.Content)
	}
	if len(ids) == 0 {
		return "", nil
	}
	return promptHeader + block.String(), ids
}

func grams(text string) map[[2]uint16]struct{} {
	text = strings.Map(func(r rune) rune {
		if strings.ContainsRune(" \t\n\v\f\r", r) {
			return -1
		}
		return r
	}, strings.ToLower(text))
	units := utf16.Encode([]rune(text))
	result := make(map[[2]uint16]struct{})
	for i := 1; i < len(units); i++ {
		result[[2]uint16{units[i-1], units[i]}] = struct{}{}
	}
	return result
}
