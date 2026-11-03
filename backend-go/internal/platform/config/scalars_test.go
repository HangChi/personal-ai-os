package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestJavaConfigurationScalar228Fixtures(t *testing.T) {
	data, err := os.ReadFile("testdata/java-scalars.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Baseline string `json:"baseline_commit"`
		Cases    []struct {
			Kind      string  `json:"kind"`
			Input     string  `json:"input"`
			JavaError bool    `json:"java_error"`
			Null      bool    `json:"null"`
			Value     *string `json:"value"`
			Overflow  bool    `json:"go_overflow"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if fixture.Baseline != "cb5d694d62fa93742b5789b97273e76d931ce335" || len(fixture.Cases) != 228 {
		t.Fatal("配置转换基线/用例数错误")
	}
	for i, test := range fixture.Cases {
		t.Run(fmt.Sprintf("%s-%d", test.Kind, i), func(t *testing.T) {
			var value *int64
			var err error
			if test.Kind == "duration" {
				var duration *time.Duration
				duration, err = ParseDuration(test.Input)
				if duration != nil {
					nanos := int64(*duration)
					value = &nanos
				}
			} else {
				value, err = ParseDataSize(test.Input)
			}
			if test.JavaError || test.Overflow {
				if err == nil || value != nil {
					t.Fatalf("必须拒绝非法/溢出配置: %q -> %v %v", test.Input, value, err)
				}
				if test.Overflow && !errors.Is(err, ErrScalarOverflow) {
					t.Fatal("Go范围收紧须可辨认", err)
				}
				return
			}
			if err != nil {
				t.Fatalf("Java接受但Go拒绝%q: %v", test.Input, err)
			}
			if test.Null {
				if value != nil {
					t.Fatal("空转换必须保持null")
				}
			} else if value == nil || test.Value == nil || strconv.FormatInt(*value, 10) != *test.Value {
				t.Fatalf("Java/Go值差分: %q got=%v want=%v", test.Input, value, test.Value)
			}
		})
	}
}

func TestScalarParsingRedactionAndReturnedValueIndependence(t *testing.T) {
	for _, input := range []string{"fixture-private-config", strings.Repeat("9", 10000) + "ms", "\x00private"} {
		_, durationErr := ParseDuration(input)
		_, sizeErr := ParseDataSize(input)
		if durationErr == nil || sizeErr == nil || strings.Contains(durationErr.Error(), "private") || strings.Contains(sizeErr.Error(), "private") || len(durationErr.Error()) > 100 || len(sizeErr.Error()) > 100 {
			t.Fatal("解析错误不能带配置原文")
		}
	}
	duration, err := ParseDuration("120s")
	if err != nil {
		t.Fatal(err)
	}
	*duration = 0
	size, err := ParseDataSize("8MB")
	if err != nil {
		t.Fatal(err)
	}
	*size = 0
	var wait sync.WaitGroup
	for i := 0; i < 100; i++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			d, e := ParseDuration("PT2M")
			if e != nil || d == nil || *d != 2*time.Minute {
				t.Error("解析结果共享可变状态")
			}
			s, e := ParseDataSize("8MB")
			if e != nil || s == nil || *s != 8*1024*1024 {
				t.Error("大小解析共享可变状态")
			}
		}()
	}
	wait.Wait()
}

func TestDurationISOIntermediateOverflowAndCancellation(t *testing.T) {
	// Java先逐分量和逐次加法检查64位秒数，不能让大数相消掩盖中间溢出。
	for _, value := range []string{"PT2562047788015216H-9223372036854775807S", "P106751991167301DT15H-9223372036854775807S"} {
		if duration, err := ParseDuration(value); err == nil || duration != nil {
			t.Fatal("ISO中间溢出不能被相消", value)
		}
	}
	for _, value := range []string{"PT2562047788015215H-9223372036854774000S", "PT+1H-60M"} {
		if duration, err := ParseDuration(value); err != nil || duration == nil || *duration != 0 {
			t.Fatal("可表示的分量相消应保留", value, err)
		}
	}
}
