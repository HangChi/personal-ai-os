package calendar

import "time"

type Recurrence string

const (
	None     Recurrence = "NONE"
	Daily    Recurrence = "DAILY"
	Weekly   Recurrence = "WEEKLY"
	Weekdays Recurrence = "WEEKDAYS"
	Monthly  Recurrence = "MONTHLY"
)

// Occurrences expands an inclusive interval in the configured calendar time zone.
func Occurrences(rule Recurrence, starts time.Time, until *time.Time, from, to time.Time, zone *time.Location) []time.Time {
	result := []time.Time{}
	if from.After(to) || starts.After(to) || (until != nil && starts.After(*until)) {
		return result
	}
	if rule == None || rule == "" {
		if !starts.Before(from) {
			result = append(result, starts.UTC())
		}
		return result
	}

	start := starts.In(zone)
	cursor := start
	localFrom := from.In(zone)
	step := 1
	switch rule {
	case Weekly:
		step = 7
	case Daily, Weekdays, Monthly:
	default:
		return result
	}

	offset := 0
	fromDate := wallDate(localFrom)
	startDate := wallDate(start)
	if fromDate.After(startDate) && clockTime(localFrom) < clockTime(start) {
		fromDate = fromDate.AddDate(0, 0, -1)
	}
	if rule == Monthly {
		months := (fromDate.Year()-start.Year())*12 + int(fromDate.Month()-start.Month())
		offset = max(0, (months*32+fromDate.Day()-start.Day())/32-1)
	} else {
		days := (fromDate.Unix() - startDate.Unix()) / 86400
		jump := max(0, int(days)/step-1) * step
		if jump > 0 {
			cursor = addDays(start, jump)
		}
	}
	for i := 0; i < 20_000; i++ {
		if rule == Monthly && offset != 0 {
			cursor = addMonths(start, offset)
		}
		if cursor.After(to) || (until != nil && cursor.After(*until)) {
			break
		}
		if !cursor.Before(from) && (rule != Weekdays || (cursor.Weekday() != time.Saturday && cursor.Weekday() != time.Sunday)) {
			result = append(result, cursor.UTC())
		}
		if rule == Monthly {
			offset++
		} else {
			cursor = addDays(cursor, step)
		}
	}
	return result
}

func wallDate(t time.Time) time.Time {
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
}

func addDays(t time.Time, days int) time.Time {
	return atWallTime(wallDate(t).AddDate(0, 0, days), t)
}

func addMonths(t time.Time, months int) time.Time {
	first := time.Date(t.Year(), t.Month()+time.Month(months), 1, 0, 0, 0, 0, time.UTC)
	lastDay := first.AddDate(0, 1, -1).Day()
	return atWallTime(first.AddDate(0, 0, min(t.Day(), lastDay)-1), t)
}

func clockTime(t time.Time) time.Duration {
	return time.Duration(t.Hour())*time.Hour + time.Duration(t.Minute())*time.Minute +
		time.Duration(t.Second())*time.Second + time.Duration(t.Nanosecond())
}

func atWallTime(date, previous time.Time) time.Time {
	wall := date.Add(clockTime(previous))
	_, preferred := previous.Zone()
	return resolveWallTime(wall, previous.Location(), preferred)
}

func resolveWallTime(wall time.Time, zone *time.Location, preferred int) time.Time {
	// Preserve the previous offset during an overlap when possible. In a gap,
	// advance by the offset change, matching ZonedDateTime.
	var earliest, forward time.Time
	for _, probe := range []time.Time{wall.Add(-48 * time.Hour), wall, wall.Add(48 * time.Hour)} {
		_, offset := probe.In(zone).Zone()
		candidate := wall.Add(-time.Duration(offset) * time.Second).In(zone)
		_, actual := candidate.Zone()
		if actual == offset {
			if offset == preferred {
				return candidate
			}
			if earliest.IsZero() || candidate.Before(earliest) {
				earliest = candidate
			}
		} else if actual > offset && (forward.IsZero() || candidate.Before(forward)) {
			forward = candidate
		}
	}
	if !earliest.IsZero() {
		return earliest
	}
	return forward
}
