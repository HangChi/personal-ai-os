package dataimport

import (
	"bytes"
	"encoding/json"
	"errors"
	"math/big"
	web "personal-ai-os/backend-go/internal/platform/http"
	"reflect"
	"strconv"
	"strings"
	"time"
)

// Filter exact record field names before decoding: encoding/json otherwise accepts
// case variants that Jackson treats as unknown, including the format version.
func bind(raw json.RawMessage, typ reflect.Type) (json.RawMessage, error) {
	if bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return raw, nil
	}
	for typ.Kind() == reflect.Pointer {
		typ = typ.Elem()
	}
	if typ == reflect.TypeFor[UUID]() || typ == reflect.TypeFor[time.Time]() {
		return raw, nil
	}
	switch typ.Kind() {
	case reflect.Struct:
		var object map[string]json.RawMessage
		if e := json.Unmarshal(raw, &object); e != nil {
			return nil, e
		}
		filtered := map[string]json.RawMessage{}
		for i := 0; i < typ.NumField(); i++ {
			field := typ.Field(i)
			key := strings.Split(field.Tag.Get("json"), ",")[0]
			if value, exists := object[key]; exists && key != "" && key != "-" {
				value, e := bind(value, field.Type)
				if e != nil {
					return nil, e
				}
				filtered[key] = value
			}
		}
		return json.Marshal(filtered)
	case reflect.Slice:
		var values []json.RawMessage
		if e := json.Unmarshal(raw, &values); e != nil {
			return nil, e
		}
		for i, value := range values {
			bound, e := bind(value, typ.Elem())
			if e != nil {
				return nil, e
			}
			values[i] = bound
		}
		return json.Marshal(values)
	case reflect.String:
		value, e := web.JSONStringField(map[string]json.RawMessage{"v": raw}, "v")
		if e != nil {
			return nil, e
		}
		return json.Marshal(value)
	case reflect.Bool:
		value, e := web.JSONBoolField(map[string]json.RawMessage{"v": raw}, "v")
		if e != nil {
			return nil, e
		}
		return json.Marshal(value)
	case reflect.Int:
		value, e := web.JSONIntField(map[string]json.RawMessage{"v": raw}, "v")
		if e != nil {
			return nil, e
		}
		return json.Marshal(value)
	case reflect.Int64:
		value := string(raw)
		if len(value) > 0 && value[0] == '"' {
			if e := json.Unmarshal(raw, &value); e != nil {
				return nil, e
			}
			value = strings.TrimFunc(value, func(r rune) bool { return r <= 0x20 })
			if value == "" || value == "null" {
				return []byte("null"), nil
			}
			n, e := strconv.ParseInt(value, 10, 64)
			if e != nil {
				return nil, e
			}
			return json.Marshal(n)
		}
		n, ok := new(big.Rat).SetString(value)
		if !ok {
			return nil, errors.New("integer required")
		}
		i := new(big.Int).Quo(n.Num(), n.Denom())
		if !i.IsInt64() {
			return nil, errors.New("integer overflow")
		}
		return json.Marshal(i.Int64())
	}
	return raw, nil
}
