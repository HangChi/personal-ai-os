// Package schedule implements the explicitly supported six-field Spring cron subset.
package schedule

import (
	"errors"
	"strconv"
	"strings"
	"time"
)

type Cron struct {
	disabled bool
	fields   [6]map[int]bool
}

func (c Cron) Disabled() bool { return c.disabled }

func Parse(expression string) (Cron, error) {
	var c Cron
	expression = strings.ToUpper(strings.TrimSpace(expression))
	if expression == "-" {
		c.disabled = true
		return c, nil
	}
	macros := map[string]string{"@YEARLY": "0 0 0 1 1 *", "@ANNUALLY": "0 0 0 1 1 *", "@MONTHLY": "0 0 0 1 * *", "@WEEKLY": "0 0 0 * * 0", "@DAILY": "0 0 0 * * *", "@MIDNIGHT": "0 0 0 * * *", "@HOURLY": "0 0 * * * *"}
	if v := macros[expression]; v != "" {
		expression = v
	}
	parts := strings.Fields(expression)
	bad := errors.New("cron无效：仅支持六字段数字/名称、范围、列表、步长、?及标准宏；不支持L/W/#")
	if len(parts) != 6 {
		return c, bad
	}
	for i, name := range []string{"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"} {
		parts[4] = strings.ReplaceAll(parts[4], name, strconv.Itoa(i+1))
	}
	for i, name := range []string{"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"} {
		parts[5] = strings.ReplaceAll(parts[5], name, strconv.Itoa(i))
	}
	minima, maxima := [6]int{0, 0, 0, 1, 1, 0}, [6]int{59, 59, 23, 31, 12, 7}
	for field, part := range parts {
		c.fields[field] = map[int]bool{}
		if part == "?" && (field == 3 || field == 5) {
			part = "*"
		}
		for _, entry := range strings.Split(part, ",") {
			step := 1
			bounds := strings.Split(entry, "/")
			if len(bounds) > 2 {
				return c, bad
			}
			if len(bounds) == 2 {
				var err error
				step, err = strconv.Atoi(bounds[1])
				if err != nil || step < 1 || step > maxima[field]+1 {
					return c, bad
				}
			}
			lo, hi := minima[field], maxima[field]
			if bounds[0] != "*" {
				rangeParts := strings.Split(bounds[0], "-")
				if len(rangeParts) > 2 {
					return c, bad
				}
				var err error
				lo, err = strconv.Atoi(rangeParts[0])
				if err != nil {
					return c, bad
				}
				hi = lo
				if len(rangeParts) == 2 {
					hi, err = strconv.Atoi(rangeParts[1])
				} else if len(bounds) == 2 {
					hi = maxima[field]
				}
				if err != nil {
					return c, bad
				}
			}
			if lo < minima[field] || hi > maxima[field] || lo > hi {
				return c, bad
			}
			for n := lo; n <= hi; n += step {
				v := n
				if field == 5 && v == 7 {
					v = 0
				}
				c.fields[field][v] = true
			}
		}
	}
	return c, nil
}

func (c Cron) Matches(now time.Time) bool {
	if c.disabled {
		return false
	}
	for i, v := range []int{now.Second(), now.Minute(), now.Hour(), now.Day(), int(now.Month()), int(now.Weekday())} {
		if !c.fields[i][v] {
			return false
		}
	}
	return true
}
