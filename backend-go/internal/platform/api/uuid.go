package api

import (
	"fmt"
	"strconv"
	"strings"
)

// ParseJavaUUID keeps UUID.fromString's padding and truncation for shorter groups.
func ParseJavaUUID(raw string) (string, bool) {
	parts := strings.Split(raw, "-")
	if len(raw) > 36 || len(parts) != 5 {
		return "", false
	}
	widths := []int{8, 4, 4, 4, 12}
	for i, part := range parts {
		number, err := strconv.ParseInt(part, 16, 64)
		if err != nil || number < 0 {
			return "", false
		}
		mask := uint64(1)<<(widths[i]*4) - 1
		parts[i] = fmt.Sprintf("%0*x", widths[i], uint64(number)&mask)
	}
	return strings.Join(parts, "-"), true
}
