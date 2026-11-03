package model

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"math"
	"net/http"
	"regexp"
	"strconv"
	"strings"

	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
)

type HTTP struct {
	service *Service
	probe   *Probe
	audio   *AudioService
}

func NewHTTP(service *Service, probe *Probe, audio *AudioService, logger *slog.Logger) http.Handler {
	h := &HTTP{service, probe, audio}
	return web.HandleErrors(h.serve, logger)
}

const modelPath = "/api/v1/settings/model"

var uuid = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func respond(w http.ResponseWriter, r *http.Request, data any) error {
	media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
	if !ok {
		return web.NotAcceptable()
	}
	if r.Method == "HEAD" {
		w.Header().Set("Content-Type", media)
		w.WriteHeader(200)
		return nil
	}
	return api.WriteControllerMediaJSON(w, 200, api.OK(data), media)
}
func (h *HTTP) serve(w http.ResponseWriter, r *http.Request) error {
	path := r.URL.Path
	method := r.Method
	read := method == "GET" || method == "HEAD"
	if path == modelPath {
		if read {
			v, e := h.service.Config(r.Context())
			if e != nil {
				return e
			}
			return respond(w, r, v)
		}
		if method != "PUT" {
			return web.MethodNotSupported(method, []string{"GET", "PUT"})
		}
		object, e := web.DecodeJSONObject(w, r, 10<<20)
		if e != nil {
			return e
		}
		c, e := decodeSave(object)
		if e != nil {
			return e
		}
		v, e := h.service.Save(r.Context(), c)
		if e != nil {
			return e
		}
		return respond(w, r, v)
	}
	if path == modelPath+"/test" || path == modelPath+"/profiles/test" {
		if method != "POST" {
			return web.MethodNotSupported(method, []string{"POST"})
		}
		return respond(w, r, h.service.runtime.Test(r.Context()))
	}
	if path == modelPath+"/profiles" {
		if read {
			v, e := h.service.Profiles(r.Context())
			if e != nil {
				return e
			}
			return respond(w, r, v)
		}
		if method != "POST" {
			return web.MethodNotSupported(method, []string{"GET", "POST"})
		}
		object, e := web.DecodeJSONObject(w, r, 10<<20)
		if e != nil {
			return e
		}
		c, e := decodeProfile(object)
		if e != nil {
			return e
		}
		v, e := h.service.Create(r.Context(), c)
		if e != nil {
			return e
		}
		return respond(w, r, v)
	}
	if path == modelPath+"/profiles/order" {
		if method != "PUT" {
			return web.MethodNotSupported(method, []string{"PUT"})
		}
		object, e := web.DecodeJSONObject(w, r, 10<<20)
		if e != nil {
			return e
		}
		ids, e := stringList(object, "ids")
		if e != nil {
			return e
		}
		values := []string{}
		for _, id := range ids {
			if id == nil {
				continue
			}
			if !uuid.MatchString(*id) {
				return web.InvalidBody(nil)
			}
			values = append(values, strings.ToLower(*id))
		}
		v, e := h.service.Reorder(r.Context(), values)
		if e != nil {
			return e
		}
		return respond(w, r, v)
	}
	if path == modelPath+"/profiles/probe-models" || path == modelPath+"/profiles/probe-window" {
		if method != "POST" {
			return web.MethodNotSupported(method, []string{"POST"})
		}
		object, e := web.DecodeJSONObject(w, r, 10<<20)
		if e != nil {
			return e
		}
		fields := map[string]*string{}
		for _, k := range []string{"protocol", "baseUrl", "apiKey", "profileId", "model"} {
			fields[k], e = web.JSONStringField(object, k)
			if e != nil {
				return e
			}
		}
		if id := fields["profileId"]; id != nil && !uuid.MatchString(*id) {
			return web.InvalidBody(nil)
		}
		target, e := h.service.ProbeTarget(r.Context(), fields["protocol"], fields["baseUrl"], fields["apiKey"], fields["profileId"])
		if e != nil {
			return e
		}
		if strings.HasSuffix(path, "probe-window") {
			v, e := h.probe.Window(r.Context(), target, value(fields["model"]))
			if e != nil {
				return e
			}
			return respond(w, r, v)
		}
		preferred, e := stringList(object, "preferredModels")
		if e != nil {
			return e
		}
		names := []string{}
		for _, v := range preferred {
			if v != nil {
				names = append(names, *v)
			}
		}
		return respond(w, r, h.probe.Models(r.Context(), target, names))
	}
	if strings.HasPrefix(path, modelPath+"/profiles/") {
		parts := strings.Split(strings.TrimPrefix(path, modelPath+"/profiles/"), "/")
		if len(parts) > 2 {
			return web.NotFound()
		}
		if !uuid.MatchString(parts[0]) {
			return web.InvalidParameter("id")
		}
		id := strings.ToLower(parts[0])
		if len(parts) == 2 {
			if parts[1] != "activate" {
				return web.NotFound()
			}
			if method != "POST" {
				return web.MethodNotSupported(method, []string{"POST"})
			}
			v, e := h.service.Activate(r.Context(), id)
			if e != nil {
				return e
			}
			return respond(w, r, v)
		}
		switch method {
		case "PUT":
			object, e := web.DecodeJSONObject(w, r, 10<<20)
			if e != nil {
				return e
			}
			c, e := decodeProfile(object)
			if e != nil {
				return e
			}
			v, e := h.service.Update(r.Context(), id, c)
			if e != nil {
				return e
			}
			return respond(w, r, v)
		case "DELETE":
			if e := h.service.Delete(r.Context(), id); e != nil {
				return e
			}
			return respond(w, r, struct {
				Deleted bool `json:"deleted"`
			}{true})
		default:
			return web.MethodNotSupported(method, []string{"PUT", "DELETE"})
		}
	}
	if path == "/api/v1/chat/transcribe/status" {
		if !read {
			return web.MethodNotSupported(method, []string{"GET"})
		}
		v, e := h.audio.Status(r.Context())
		if e != nil {
			return e
		}
		return respond(w, r, v)
	}
	if path == "/api/v1/chat/transcribe" {
		if method != "POST" {
			return web.MethodNotSupported(method, []string{"POST"})
		}
		upload, e := web.RequestMultipart(w, r)
		if e != nil {
			return e
		}
		files := upload.Files["file"]
		if len(files) == 0 {
			return web.MissingParameter("file")
		}
		file := files[0]
		if file.Size == 0 {
			return validation("录音文件不能为空")
		}
		reader, e := file.Open()
		if e != nil {
			return validation("读取录音文件失败，请重试")
		}
		defer reader.Close()
		data, e := io.ReadAll(io.LimitReader(reader, MaxAudioBytes+1))
		if e != nil {
			return validation("读取录音文件失败，请重试")
		}
		v, e := h.audio.Transcribe(r.Context(), data, file.Filename, value(file.ContentType))
		if e != nil {
			return e
		}
		return respond(w, r, v)
	}
	return web.NotFound()
}
func decodeSave(o map[string]json.RawMessage) (SaveCommand, error) {
	var c SaveCommand
	for name, p := range map[string]**string{"baseUrl": &c.BaseURL, "apiKey": &c.APIKey, "chatModel": &c.ChatModel, "embeddingModel": &c.EmbeddingModel} {
		v, e := web.JSONStringField(o, name)
		if e != nil {
			return c, e
		}
		*p = v
	}
	if raw, ok := o["temperature"]; ok && !nullJSON(raw) {
		var text string
		if e := json.Unmarshal(raw, &text); e != nil {
			text = string(raw)
		}
		v := parseTemperature(text)
		if v == nil {
			return c, web.InvalidBody(nil)
		}
		c.Temperature = v
	}
	return c, nil
}
func nullJSON(raw json.RawMessage) bool { return bytes.Equal(bytes.TrimSpace(raw), []byte("null")) }
func stringList(o map[string]json.RawMessage, name string) ([]*string, error) {
	raw, ok := o[name]
	if !ok || nullJSON(raw) {
		return nil, nil
	}
	var values []json.RawMessage
	if e := json.Unmarshal(raw, &values); e != nil {
		return nil, web.InvalidBody(e)
	}
	out := []*string{}
	for _, v := range values {
		text, e := web.JSONStringField(map[string]json.RawMessage{"v": v}, "v")
		if e != nil {
			return nil, e
		}
		out = append(out, text)
	}
	return out, nil
}
func decodeProfile(o map[string]json.RawMessage) (ProfileCommand, error) {
	c := ProfileCommand{}
	var e error
	c.SaveCommand, e = decodeSave(o)
	if e != nil {
		return c, e
	}
	for name, p := range map[string]**string{"name": &c.Name, "presetId": &c.PresetID, "protocol": &c.Protocol} {
		v, e := web.JSONStringField(o, name)
		if e != nil {
			return c, e
		}
		*p = v
	}
	for name, p := range map[string]*[]*string{"chatModels": &c.ChatModels, "embeddingModels": &c.EmbeddingModels, "audioModels": &c.AudioModels} {
		*p, e = stringList(o, name)
		if e != nil {
			return c, e
		}
	}
	if raw, ok := o["contextWindows"]; ok && !nullJSON(raw) {
		var values map[string]json.RawMessage
		if e = json.Unmarshal(raw, &values); e != nil {
			return c, web.InvalidBody(e)
		}
		c.ContextWindows = map[string]*int{}
		for name, raw := range values {
			if nullJSON(raw) {
				c.ContextWindows[name] = nil
				continue
			}
			var text string
			if e = json.Unmarshal(raw, &text); e != nil {
				text = string(raw)
			}
			f, e := strconv.ParseFloat(text, 64)
			if e != nil || math.IsNaN(f) || math.IsInf(f, 0) || f > math.MaxInt32 || f < math.MinInt32 {
				return c, web.InvalidBody(e)
			}
			v := int(f)
			c.ContextWindows[name] = &v
		}
	}
	return c, nil
}
