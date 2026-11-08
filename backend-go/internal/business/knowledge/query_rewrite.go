package knowledge

import (
	"personal-ai-os/backend-go/internal/platform/config"
	"strconv"
)

// Resolve the new property and .env alias without changing the immutable migration baseline.
func queryRewriteEnabled(properties *config.Properties) (bool, error) {
	for _, key := range []string{"app.knowledge.rag.query-rewrite-enabled", "RAG_QUERY_REWRITE_ENABLED"} {
		value, found, err := properties.Get(key)
		if err != nil {
			return false, err
		}
		if found {
			return strconv.ParseBool(value)
		}
	}
	return true, nil
}
