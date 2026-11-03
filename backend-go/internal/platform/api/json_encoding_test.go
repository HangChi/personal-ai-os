package api

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func TestControllerJSONCharsetsEncodeActualBytes(t *testing.T) {
	value := map[string]string{"message": "你好😀"}
	for _, test := range []struct {
		charset string
		prefix  []byte
	}{
		{"UTF-8", []byte{'{'}},
		{"UTF-16BE", []byte{0, '{'}},
		{"UTF-16LE", []byte{'{', 0}},
		{"UTF-32BE", []byte{0, 0, 0, '{'}},
		{"UTF-32LE", []byte{'{', 0, 0, 0}},
	} {
		t.Run(test.charset, func(t *testing.T) {
			contentType := "application/json;charset=" + test.charset
			w := httptest.NewRecorder()
			if err := WriteControllerMediaJSON(w, 200, value, contentType); err != nil {
				t.Fatal(err)
			}
			if w.Code != 200 || w.Header().Get("Content-Type") != contentType || !bytes.HasPrefix(w.Body.Bytes(), test.prefix) {
				t.Fatal("只修改了声明而未按真实字节编码")
			}
			codec, err := JSONEncoding(test.charset)
			if err != nil {
				t.Fatal(err)
			}
			data, err := codec.NewDecoder().Bytes(w.Body.Bytes())
			var got map[string]string
			if err != nil || json.Unmarshal(data, &got) != nil || got["message"] != value["message"] {
				t.Fatal("中文或非BMP字符被截断", err)
			}
		})
	}
}

func TestUnsupportedJSONEncodingFailsBeforeResponseCommit(t *testing.T) {
	for _, charset := range []string{"ISO-8859-1", "UTF-16", "UTF-32", "unsupported"} {
		w := httptest.NewRecorder()
		err := WriteControllerMediaJSON(w, 200, map[string]string{"private": "fixture-secret"}, "application/json;charset="+charset)
		var result Response[any]
		if err == nil || w.Code != 500 || w.Header().Get("Content-Type") != "application/json" || json.Unmarshal(w.Body.Bytes(), &result) != nil || result.Code != InternalError || result.Data != nil || bytes.Contains(w.Body.Bytes(), []byte("fixture-secret")) {
			t.Fatal("编码失败留下成功头或泄露原始数据", charset)
		}
	}
}
