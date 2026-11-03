package config

import (
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"strings"
	"sync"
	"testing"
)

func TestJavaConfigDataProperties(t *testing.T) {
	data, err := os.ReadFile("testdata/java-config-data.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Keys  []string `json:"keys"`
		Cases []struct {
			System    map[string]string `json:"system"`
			Overrides map[string]string `json:"overrides"`
			DotEnv    map[string]string `json:"dotenv"`
			Error     bool              `json:"java_error"`
			Value     struct {
				Profiles   []string       `json:"profiles"`
				Defaults   []string       `json:"defaults"`
				Properties map[string]any `json:"properties"`
			} `json:"value"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Cases) < 122 || len(fixture.Keys) < 189 {
		t.Fatal("ConfigData对照缺失")
	}
	for index, row := range fixture.Cases {
		t.Run(fmt.Sprintf("case-%03d", index), func(t *testing.T) {
			properties, err := LoadBaselineProperties(PropertyOptions{Overrides: row.Overrides, System: row.System, DotEnv: row.DotEnv})
			if row.Error {
				if err == nil || properties != nil {
					t.Fatal("Java拒绝的profile必须拒绝")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(properties.Profiles(), row.Value.Profiles) || !reflect.DeepEqual(properties.DefaultProfiles(), row.Value.Defaults) {
				t.Fatalf("profile不同：%v %v", properties.Profiles(), properties.DefaultProfiles())
			}
			for _, key := range fixture.Keys {
				text, found, err := properties.Get(key)
				want := row.Value.Properties[key]
				if _, failed := want.(map[string]any); failed {
					if err == nil {
						t.Fatal("未拒绝无法解析的占位符", key)
					}
					continue
				}
				if err != nil {
					t.Fatal(key, err)
				}
				if want == nil {
					if found {
						t.Fatal("缺省字段被伪造", key)
					}
					continue
				}
				if !found || text != want {
					t.Fatalf("%s 与真实ConfigData不同：want=%q got=%q", key, want, text)
				}
			}
		})
	}
}

func TestPropertiesIsolationRedactionAndUnsupportedLocations(t *testing.T) {
	options := PropertyOptions{System: map[string]string{"AUTH_PASSWORD": "fixture-private-password", "SPRING_PROFILES_ACTIVE": "worker-index"}}
	properties, err := LoadBaselineProperties(options)
	if err != nil {
		t.Fatal(err)
	}
	options.System["AUTH_PASSWORD"] = "changed"
	properties.Profiles()[0] = "changed"
	for _, value := range []any{properties, &properties, options, &options} {
		for _, format := range []string{"%v", "%+v", "%#v"} {
			if strings.Contains(fmt.Sprintf(format, value), "fixture-private") {
				t.Fatal("配置格式化泄密")
			}
		}
		encoded, err := json.Marshal(value)
		if err != nil || strings.Contains(string(encoded), "fixture-private") {
			t.Fatal("配置JSON泄密")
		}
	}
	for _, name := range []string{"SPRING_CONFIG_LOCATION", "spring.config.import", "SPRING_CONFIG_ADDITIONAL_LOCATION", "spring.config.name", "spring.profiles.group.local[0]"} {
		if _, err := LoadBaselineProperties(PropertyOptions{System: map[string]string{name: "fixture-private-location"}}); err == nil || strings.Contains(err.Error(), "fixture-private") {
			t.Fatal("未验证外部配置必须显式拒绝", name)
		}
	}
	for _, source := range []PropertyOptions{{System: map[string]string{"AUTH_PASSWORD": string([]byte{0xff})}}, {Overrides: map[string]string{string([]byte{0xff}): "value"}}} {
		if _, err := LoadBaselineProperties(source); err == nil {
			t.Fatal("非法UTF-8被接受")
		}
	}
	var workers sync.WaitGroup
	for range 100 {
		workers.Go(func() {
			for range 10 {
				value, found, err := properties.Get("app.auth.password")
				if err != nil || !found || value != "fixture-private-password" || properties.Profiles()[0] != "worker-index" {
					t.Error("配置读取串扰", err)
					return
				}
			}
		})
	}
	workers.Wait()
}
