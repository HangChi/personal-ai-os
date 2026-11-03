package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
)

func boundRecord(kind string, input map[string]string) (any, error) {
	switch kind {
	case "auth":
		value, err := BindAuthProperties(input)
		if err != nil {
			return nil, err
		}
		return map[string]any{"enabled": value.Enabled, "username": value.Username,
			"password": value.Password(), "maxFailures": value.MaxFailures,
			"lockout": strconv.FormatInt(int64(value.Lockout), 10)}, nil
	case "rate-limit":
		value, err := BindRateLimitProperties(input)
		if err != nil {
			return nil, err
		}
		return map[string]any{"enabled": value.Enabled, "capacity": value.Capacity,
			"period": strconv.FormatInt(int64(value.Period), 10)}, nil
	case "agent":
		value, err := BindAgentExecutionProperties(input)
		if err != nil {
			return nil, err
		}
		return map[string]any{"maxModelCalls": value.MaxModelCalls,
			"maxConsecutiveFailures": value.MaxConsecutiveFailures}, nil
	default:
		return nil, errors.New("未知配置夹具")
	}
}

func TestJavaRecordBinding(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("testdata", "java-binding.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Cases []struct {
			Kind      string            `json:"kind"`
			Input     map[string]string `json:"input"`
			JavaError bool              `json:"java_error"`
			Overflow  bool              `json:"go_overflow"`
			Value     json.RawMessage   `json:"value"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Cases) < 197 {
		t.Fatal("绑定夹具缺失")
	}
	for index, test := range fixture.Cases {
		t.Run(fmt.Sprintf("%s-%03d", test.Kind, index), func(t *testing.T) {
			value, err := boundRecord(test.Kind, test.Input)
			if test.JavaError || test.Overflow {
				if err == nil || value != nil {
					t.Fatal("非法或超范围配置必须拒绝")
				}
				if test.Overflow && !errors.Is(err, ErrScalarOverflow) {
					t.Fatal("必须保留溢出原因", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			actual, err := json.Marshal(value)
			if err != nil {
				t.Fatal(err)
			}
			var want, got any
			if err := json.Unmarshal(test.Value, &want); err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal(actual, &got); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(want, got) {
				t.Fatalf("与真实Binder不一致：want=%s got=%s", test.Value, actual)
			}
		})
	}
}

func TestAuthPropertiesDoNotLeakOrAliasCredentials(t *testing.T) {
	input := map[string]string{"app.auth.username": "fixture-private-user", "app.auth.password": "fixture-private-password"}
	value, err := BindAuthProperties(input)
	if err != nil {
		t.Fatal(err)
	}
	input["app.auth.password"] = "changed-source"
	password := value.Password()
	if password == nil || *password != "fixture-private-password" {
		t.Fatal("绑定值被输入修改")
	}
	*password = "changed-return"
	if *value.Password() != "fixture-private-password" {
		t.Fatal("密码指针泄漏内部状态")
	}
	for _, format := range []string{"%v", "%+v", "%#v", "%s"} {
		if strings.Contains(fmt.Sprintf(format, value), "fixture-private") ||
			strings.Contains(fmt.Sprintf(format, &value), "fixture-private") {
			t.Fatal("格式化泄漏凭据")
		}
	}
	encoded, err := json.Marshal(value)
	if err != nil || strings.Contains(string(encoded), "fixture-private") {
		t.Fatal("JSON泄漏凭据")
	}
	for _, key := range []string{"app.auth.enabled", "app.auth.lockout", "app.auth.max-failures"} {
		_, err := BindAuthProperties(map[string]string{key: "fixture-private-password"})
		if err == nil || strings.Contains(err.Error(), "fixture-private") {
			t.Fatal("错误回显配置值")
		}
	}
	// Unicode非法字节不能被标准库静默替换为另一个用户名/密码。
	for _, key := range []string{"app.auth.username", "app.auth.password"} {
		if _, err := BindAuthProperties(map[string]string{key: string([]byte{0xff})}); err == nil {
			t.Fatal("非法UTF-8被接受")
		}
	}
}

func TestRecordBindingConcurrentAndIndependent(t *testing.T) {
	input := map[string]string{"app.auth.password": "fixture-only-concurrent", "app.auth.max-failures": "7"}
	var workers sync.WaitGroup
	for range 100 {
		workers.Go(func() {
			for range 10 {
				value, err := BindAuthProperties(input)
				if err != nil || value.MaxFailures != 7 || *value.Password() != "fixture-only-concurrent" {
					t.Error("配置绑定串扰", err)
					return
				}
				password := value.Password()
				*password = "local-change"
				if _, err := BindRateLimitProperties(nil); err != nil {
					t.Error(err)
				}
				if _, err := BindAgentExecutionProperties(nil); err != nil {
					t.Error(err)
				}
			}
		})
	}
	workers.Wait()
}
