package calendar

import (
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf16"
	"unicode/utf8"
)

// 严格子集：不能映射的语义整批拒绝，不静默丢失重复、邀请或例外。
type ICSEntry struct {
	UID   string       `json:"uid"`
	Event EventRequest `json:"event"`
}
type icsProperty struct {
	name, value string
	params      map[string]string
}

func icsError(s string) error { return validation("ICS：" + s) }
func parseICS(content string, zone *time.Location) ([]ICSEntry, error) {
	if len(content) > 1<<20 || !utf8.ValidString(content) {
		return nil, icsError("文件无效或超过 1MiB")
	}
	text := strings.ReplaceAll(content, "\r\n", "\n")
	if strings.ContainsAny(text, "\r\x00") {
		return nil, icsError("非法换行或字符")
	}
	lines := []string{}
	for _, line := range strings.Split(text, "\n") {
		if strings.HasPrefix(line, " ") || strings.HasPrefix(line, "\t") {
			if len(lines) == 0 {
				return nil, icsError("非法折行")
			}
			lines[len(lines)-1] += line[1:]
		} else if line != "" {
			lines = append(lines, line)
		}
	}
	if len(lines) < 3 || lines[0] != "BEGIN:VCALENDAR" || lines[len(lines)-1] != "END:VCALENDAR" {
		return nil, icsError("必须是完整 VCALENDAR")
	}
	allowed := map[string]bool{}
	for _, key := range []string{"UID", "DTSTART", "DTEND", "SUMMARY", "DESCRIPTION", "LOCATION", "RRULE", "DTSTAMP", "CREATED", "LAST-MODIFIED", "SEQUENCE", "STATUS", "TRANSP"} {
		allowed[key] = true
	}
	var event map[string]icsProperty
	result := []ICSEntry{}
	uids := map[string]bool{}
	version := false
	for _, line := range lines[1 : len(lines)-1] {
		if line == "BEGIN:VEVENT" {
			if event != nil {
				return nil, icsError("不支持嵌套组件")
			}
			event = map[string]icsProperty{}
			continue
		}
		if line == "END:VEVENT" {
			if event == nil || len(result) >= 200 {
				return nil, icsError("最多 200 个事件")
			}
			entry, err := icsEntry(event, zone)
			if err != nil {
				return nil, err
			}
			if uids[entry.UID] {
				return nil, icsError("文件内 UID 重复")
			}
			uids[entry.UID] = true
			result = append(result, entry)
			event = nil
			continue
		}
		if strings.HasPrefix(line, "BEGIN:") || strings.HasPrefix(line, "END:") {
			return nil, icsError("不支持时区定义、提醒、待办或例外组件")
		}
		p, err := icsProp(line)
		if err != nil {
			return nil, err
		}
		if event == nil {
			if p.name == "VERSION" && p.value == "2.0" {
				version = true
			} else if p.name != "PRODID" && p.name != "CALSCALE" && !(p.name == "METHOD" && p.value == "PUBLISH") {
				return nil, icsError("不支持日历属性 " + p.name)
			}
		} else {
			if _, ok := event[p.name]; ok || !allowed[p.name] {
				return nil, icsError("不支持或重复的事件属性 " + p.name)
			}
			event[p.name] = p
		}
	}
	if !version || event != nil || len(result) == 0 {
		return nil, icsError("缺少 VERSION:2.0 或完整事件")
	}
	return result, nil
}
func icsProp(line string) (icsProperty, error) {
	colon := strings.IndexByte(line, ':')
	if colon < 1 {
		return icsProperty{}, icsError("属性格式错误")
	}
	keys := strings.Split(line[:colon], ";")
	p := icsProperty{keys[0], line[colon+1:], map[string]string{}}
	for _, key := range keys[1:] {
		pair := strings.SplitN(key, "=", 2)
		if len(pair) != 2 || (pair[0] != "TZID" && pair[0] != "VALUE") {
			return p, icsError("不支持属性参数")
		}
		if _, ok := p.params[pair[0]]; ok {
			return p, icsError("重复属性参数")
		}
		p.params[pair[0]] = strings.ReplaceAll(pair[1], "\"", "")
	}
	if len(p.params) > 0 && p.name != "DTSTART" && p.name != "DTEND" {
		return p, icsError("仅开始和结束时间支持属性参数")
	}
	return p, nil
}
func icsTime(p icsProperty, zone *time.Location) (time.Time, error) {
	var err error
	if p.params["TZID"] != "" {
		zone, err = time.LoadLocation(p.params["TZID"])
		if err != nil {
			return time.Time{}, icsError("无效时区")
		}
	}
	if p.params["VALUE"] == "DATE" {
		if p.params["TZID"] != "" {
			return time.Time{}, icsError("DATE 不能带 TZID")
		}
		t, e := time.ParseInLocation("20060102", p.value, zone)
		if e != nil {
			return t, icsError("无效日期")
		}
		return t.UTC(), nil
	}
	if p.params["VALUE"] != "" && p.params["VALUE"] != "DATE-TIME" {
		return time.Time{}, icsError("不支持的时间类型")
	}
	if strings.HasSuffix(p.value, "Z") {
		if p.params["TZID"] != "" {
			return time.Time{}, icsError("UTC 时间不能带 TZID")
		}
		t, e := time.Parse("20060102T150405Z", p.value)
		if e != nil {
			return t, icsError("无效时间")
		}
		return t, nil
	}
	t, e := time.ParseInLocation("20060102T150405", p.value, zone)
	if e != nil || t.In(zone).Format("20060102T150405") != p.value {
		return t, icsError("无效或夏令时缺失时间，请使用 UTC")
	}
	for _, d := range []time.Duration{-3 * time.Hour, -2 * time.Hour, -time.Hour, -30 * time.Minute, 30 * time.Minute, time.Hour, 2 * time.Hour, 3 * time.Hour} {
		if t.Add(d).In(zone).Format("20060102T150405") == p.value {
			return t, icsError("夏令时重叠时间，请使用 UTC")
		}
	}
	return t.UTC(), nil
}
func icsUnescape(s string) (string, error) {
	var out strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c == '\\' {
			i++
			if i == len(s) {
				return "", icsError("无效文本转义")
			}
			c = s[i]
			if c == 'n' || c == 'N' {
				c = '\n'
			} else if c != '\\' && c != ',' && c != ';' {
				return "", icsError("无效文本转义")
			}
		}
		out.WriteByte(c)
	}
	return out.String(), nil
}
func icsEntry(p map[string]icsProperty, zone *time.Location) (ICSEntry, error) {
	for _, key := range []string{"UID", "SUMMARY", "DTSTART", "DTEND"} {
		if _, ok := p[key]; !ok {
			return ICSEntry{}, icsError("缺少 " + key)
		}
	}
	uid := p["UID"].value
	if strings.TrimSpace(uid) == "" || len(utf16.Encode([]rune(uid))) > 512 || strings.IndexFunc(uid, unicode.IsControl) >= 0 {
		return ICSEntry{}, icsError("UID 无效或超过 512 字符")
	}
	title, e := icsUnescape(p["SUMMARY"].value)
	if e != nil {
		return ICSEntry{}, e
	}
	description, e := icsUnescape(p["DESCRIPTION"].value)
	if e != nil {
		return ICSEntry{}, e
	}
	location, e := icsUnescape(p["LOCATION"].value)
	if e != nil {
		return ICSEntry{}, e
	}
	title = strings.TrimSpace(title)
	if title == "" || len(utf16.Encode([]rune(title))) > 200 || len(utf16.Encode([]rune(description))) > 2000 || len(utf16.Encode([]rune(location))) > 200 {
		return ICSEntry{}, icsError("标题、描述或地点超出长度限制")
	}
	start, end := p["DTSTART"], p["DTEND"]
	allDay := start.params["VALUE"] == "DATE"
	if allDay != (end.params["VALUE"] == "DATE") {
		return ICSEntry{}, icsError("开始与结束的日期类型必须一致")
	}
	a, e := icsTime(start, zone)
	if e != nil {
		return ICSEntry{}, e
	}
	b, e := icsTime(end, zone)
	if e != nil {
		return ICSEntry{}, e
	}
	if !b.After(a) || b.Sub(a) > 366*24*time.Hour {
		return ICSEntry{}, icsError("结束必须晚于开始，跨度最多 366 天")
	}
	recurrence := None
	var until *time.Time
	if rule, ok := p["RRULE"]; ok {
		if start.params["TZID"] != "" && start.params["TZID"] != zone.String() {
			return ICSEntry{}, icsError("重复事件的时区须与应用日历一致")
		}
		if start.params["TZID"] != end.params["TZID"] || strings.HasSuffix(start.value, "Z") != strings.HasSuffix(end.value, "Z") {
			return ICSEntry{}, icsError("重复事件的时区必须一致")
		}
		parts := map[string]string{}
		for _, item := range strings.Split(rule.value, ";") {
			pair := strings.SplitN(item, "=", 2)
			if len(pair) != 2 || (pair[0] != "FREQ" && pair[0] != "UNTIL" && pair[0] != "INTERVAL" && pair[0] != "BYDAY") {
				return ICSEntry{}, icsError("不支持的重复规则")
			}
			if _, ok := parts[pair[0]]; ok {
				return ICSEntry{}, icsError("重复规则属性重复")
			}
			parts[pair[0]] = pair[1]
		}
		if parts["INTERVAL"] != "" && parts["INTERVAL"] != "1" {
			return ICSEntry{}, icsError("只支持间隔 1 的重复")
		}
		recurrence = Recurrence(parts["FREQ"])
		if recurrence != Daily && recurrence != Weekly && recurrence != Monthly {
			return ICSEntry{}, icsError("只支持 DAILY/WEEKLY/MONTHLY")
		}
		if parts["BYDAY"] != "" {
			if recurrence != Weekly || parts["BYDAY"] != "MO,TU,WE,TH,FR" {
				return ICSEntry{}, icsError("仅支持周一到周五 BYDAY")
			}
			recurrence = Weekdays
		}
		if err := icsRecurrence(recurrence, a, zone); err != nil {
			return ICSEntry{}, err
		}
		if value := parts["UNTIL"]; value != "" {
			if !allDay && !strings.HasSuffix(value, "Z") {
				return ICSEntry{}, icsError("重复截止须使用 UTC")
			}
			params := map[string]string{}
			if allDay {
				params["VALUE"] = "DATE"
			}
			t, e := icsTime(icsProperty{"UNTIL", value, params}, zone)
			if e != nil {
				return ICSEntry{}, e
			}
			if t.Before(a) {
				return ICSEntry{}, icsError("重复截止早于首次开始")
			}
			until = &t
		}
	}
	if s, ok := p["STATUS"]; ok && s.value != "CONFIRMED" {
		return ICSEntry{}, icsError("不支持取消或暂定日程")
	}
	if s, ok := p["TRANSP"]; ok && s.value != "OPAQUE" {
		return ICSEntry{}, icsError("不支持透明日程")
	}
	lead := 15
	return ICSEntry{uid, EventRequest{Title: &title, Description: &description, Location: &location, AllDay: &allDay, StartsAt: &a, EndsAt: &b, RecurrenceType: &recurrence, RecurrenceUntil: until, Lead: &lead}}, nil
}
func icsRecurrence(rule Recurrence, start time.Time, zone *time.Location) error {
	local := start.In(zone)
	if rule == Monthly && local.Day() > 28 {
		return icsError("月末29..31日的重复规则无法无损交换")
	}
	if rule == Weekdays && (local.Weekday() == time.Saturday || local.Weekday() == time.Sunday) {
		return icsError("工作日重复必须从工作日开始")
	}
	_, end := local.ZoneBounds()
	if rule != None && !end.IsZero() {
		return icsError("当前应用时区未来存在夏令时变化，暂不支持交换重复日程")
	}
	return nil
}
func icsEscape(s string) string {
	return strings.NewReplacer("\\", "\\\\", "\r", "", "\n", "\\n", ";", "\\;", ",", "\\,").Replace(s)
}
func writeICS(events []Event, zone *time.Location, sourceUIDs map[string]string) (string, error) {
	if len(events) > 10000 {
		return "", icsError("导出最多 10000 个日程")
	}
	lines := []string{"BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Personal AI OS//Calendar//ZH", "CALSCALE:GREGORIAN"}
	sort.SliceStable(events, func(i, j int) bool { return events[i].StartsAt.Before(events[j].StartsAt) })
	for _, e := range events {
		if err := icsRecurrence(e.RecurrenceType, e.StartsAt, zone); err != nil {
			return "", err
		}
		uid, ok := sourceUIDs[e.ID]
		if !ok {
			uid = e.ID + "@personal-ai-os"
		}
		lines = append(lines, "BEGIN:VEVENT", "UID:"+uid, "DTSTAMP:"+e.CreatedAt.UTC().Format("20060102T150405Z"))
		for i, t := range []time.Time{e.StartsAt, e.EndsAt} {
			key := []string{"DTSTART", "DTEND"}[i]
			if e.AllDay {
				lines = append(lines, key+";VALUE=DATE:"+t.In(zone).Format("20060102"))
			} else if e.RecurrenceType == None {
				lines = append(lines, key+":"+t.UTC().Format("20060102T150405Z"))
			} else {
				lines = append(lines, key+";TZID="+zone.String()+":"+t.In(zone).Format("20060102T150405"))
			}
		}
		description, location := "", ""
		if e.Description != nil {
			description = *e.Description
		}
		if e.Location != nil {
			location = *e.Location
		}
		lines = append(lines, "SUMMARY:"+icsEscape(e.Title), "DESCRIPTION:"+icsEscape(description), "LOCATION:"+icsEscape(location))
		if e.RecurrenceType != None {
			rule := "FREQ=" + string(e.RecurrenceType)
			if e.RecurrenceType == Weekdays {
				rule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
			}
			if e.RecurrenceUntil != nil {
				value := e.RecurrenceUntil.UTC().Format("20060102T150405Z")
				if e.AllDay {
					value = e.RecurrenceUntil.In(zone).Format("20060102")
				}
				rule += ";UNTIL=" + value
			}
			lines = append(lines, "RRULE:"+rule)
		}
		lines = append(lines, "END:VEVENT")
	}
	lines = append(lines, "END:VCALENDAR")
	var out strings.Builder
	for _, line := range lines {
		count := 0
		for _, r := range line {
			n := utf8.RuneLen(r)
			if count+n > 75 {
				out.WriteString("\r\n ")
				count = 1
			}
			out.WriteRune(r)
			count += n
		}
		out.WriteString("\r\n")
	}
	return out.String(), nil
}
