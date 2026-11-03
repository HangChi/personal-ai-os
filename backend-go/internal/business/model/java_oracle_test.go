package model

import (
	"bytes"
	"encoding/json"
	"os"
	"reflect"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

// 期望值由固定基线实际执行生成，不能从 Go 实现反推。
func TestJavaConfigurationReadOracle(t *testing.T) {
	data, err := os.ReadFile("testdata/java-config-read.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Baseline       string            `json:"baseline_commit"`
		JavaCiphertext string            `json:"javaCiphertext"`
		Plaintext      string            `json:"syntheticPlaintext"`
		Master         string            `json:"syntheticMaster"`
		Masks          map[string]string `json:"masks"`
		Cases          []struct {
			Name       string            `json:"name"`
			Properties map[string]string `json:"properties"`
			Variables  map[string]string `json:"variables"`
			Values     *Values           `json:"values"`
			Expected   json.RawMessage   `json:"expected"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if fixture.Baseline != "cb5d694d62fa93742b5789b97273e76d931ce335" || len(fixture.Cases) < 20 {
		t.Fatal("missing fixed Java baseline")
	}
	for _, test := range fixture.Cases {
		t.Run(test.Name, func(t *testing.T) {
			properties, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: test.Properties, System: test.Variables})
			if err != nil {
				t.Fatal(err)
			}
			r, err := NewResolver(properties)
			if err != nil {
				t.Fatal(err)
			}
			values := Values{}
			if test.Values != nil {
				values = *test.Values
				values.Revision = ptr(1)
				now := time.Date(2026, 11, 3, 0, 0, 0, 0, time.UTC)
				values.UpdatedAt = &now
			}
			resolved := r.Resolve(values)
			actual, err := json.Marshal(map[string]any{"name": test.Name, "dto": resolved.DTO(), "audioModel": resolved.AudioModel, "hasAudioModel": resolved.HasAudioModel()})
			if err != nil {
				t.Fatal(err)
			}
			// 数值形式由各序列化器决定（0 vs 0.0），结构/null/字符串/顺序不能忽略。
			var got, want any
			if err := json.Unmarshal(actual, &got); err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal(test.Expected, &want); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("Java/Go mismatch\nGo: %s\nJava: %s", actual, bytes.TrimSpace(test.Expected))
			}
		})
	}
	for key, mask := range fixture.Masks {
		if Mask(key) != mask {
			t.Errorf("UTF-16 mask differs from Java; expected=%q got=%q", mask, Mask(key))
		}
	}
	codec, err := NewKeyCodec(fixture.Master)
	if err != nil {
		t.Fatal(err)
	}
	plain, err := codec.Decrypt(&fixture.JavaCiphertext)
	if err != nil || plain == nil || *plain != fixture.Plaintext {
		t.Fatal("original Java ciphertext unreadable")
	}
}
