package auth

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/bcrypt"
)

func ptr(value string) *string { return &value }

func enabledOptions() Options {
	options := DefaultOptions()
	options.Enabled = true
	options.Password = "test-secret"
	return options
}

type testCodec struct{ encodes, matches int }

func (codec *testCodec) encode(string) ([]byte, error) {
	codec.encodes++
	return []byte("synthetic-hash"), nil
}
func (codec *testCodec) match(raw string, _ []byte) bool {
	codec.matches++
	return raw == "test-secret"
}

func TestServiceConfiguration(t *testing.T) {
	for _, password := range []string{"", " \t\n\u2003\u001c"} {
		options := enabledOptions()
		options.Password = password
		if _, err := New(options); err == nil || !strings.Contains(err.Error(), "AUTH_PASSWORD") {
			t.Fatalf("missing/blank password: %v", err)
		}
	}
	for _, options := range []Options{
		{MaxFailures: 0, Lockout: time.Second},
		{MaxFailures: -1, Lockout: time.Second},
		{MaxFailures: math.MaxInt32 + 1, Lockout: time.Second},
		{MaxFailures: 5}, {MaxFailures: 5, Lockout: -1},
	} {
		if _, err := New(options); err == nil {
			t.Fatal("disabled auth must also validate lockout settings")
		}
	}
	for _, row := range []struct{ input, want string }{
		{"", "admin"}, {" \u2003\u001f", "admin"}, {"\u2003owner\t", "owner"},
		{"\u00a0owner\u00a0", "\u00a0owner\u00a0"}, {"\u0085", "\u0085"},
	} {
		options := DefaultOptions()
		options.Username = row.input
		codec := &testCodec{}
		service, err := newService(options, codec, time.Now)
		if err != nil || service.Username() != row.want || service.Enabled() || codec.encodes != 0 {
			t.Fatalf("disabled constructor: %v, %v", service, err)
		}
		if ok, err := service.Verify(ptr(row.want), ptr("test-secret")); ok || err != nil || codec.matches != 0 {
			t.Fatalf("disabled verify: %t, %v", ok, err)
		}
	}
}

func TestServiceCredentialsAndRedaction(t *testing.T) {
	options := enabledOptions()
	options.MaxFailures = 100
	service, err := New(options)
	if err != nil {
		t.Fatal(err)
	}
	for _, row := range []struct {
		user, password *string
		want           bool
	}{
		{ptr("admin"), ptr("test-secret"), true},
		{ptr("\u2003 admin \t"), ptr("test-secret"), true},
		{ptr("Admin"), ptr("test-secret"), false},
		{ptr("root"), ptr("test-secret"), false},
		{ptr("admin"), ptr("wrong"), false},
		{ptr("admin"), ptr(""), false},
		{ptr("admin"), ptr(" test-secret"), false},
		{ptr("\u00a0admin\u00a0"), ptr("test-secret"), false},
		{nil, ptr("test-secret"), false}, {ptr("admin"), nil, false},
	} {
		if ok, err := service.Verify(row.user, row.password); ok != row.want || err != nil {
			t.Fatalf("verify = %t, %v; want %t", ok, err, row.want)
		}
	}
	if !service.Enabled() || service.Username() != "admin" {
		t.Fatal("public metadata")
	}
	for _, value := range []any{options, service} {
		for _, format := range []string{"%v", "%+v", "%#v"} {
			text := fmt.Sprintf(format, value)
			if strings.Contains(text, options.Password) || strings.Contains(text, "$2a$") {
				t.Fatal("credential/hash exposed through formatting")
			}
		}
	}
	typ := reflect.TypeOf(service)
	for i := 0; i < typ.NumMethod(); i++ {
		if strings.Contains(strings.ToLower(typ.Method(i).Name), "password") {
			t.Fatal("password accessor exposed")
		}
	}
	for i := 0; i < typ.Elem().NumField(); i++ {
		field := typ.Elem().Field(i)
		if field.Type == reflect.TypeOf(Options{}) {
			t.Fatal("service retained plaintext options")
		}
	}
}

func TestServiceLockoutSerialContract(t *testing.T) {
	now := time.Date(2026, 11, 3, 0, 0, 0, 0, time.UTC)
	options := enabledOptions()
	options.MaxFailures = 3
	options.Lockout = 1500 * time.Millisecond
	codec := &testCodec{}
	service, err := newService(options, codec, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	wrong := func() {
		t.Helper()
		if ok, err := service.Verify(ptr("root"), ptr("wrong")); ok || err != nil {
			t.Fatalf("failure/threshold attempt = %t, %v", ok, err)
		}
	}
	wrong()
	wrong()
	if codec.matches != 2 {
		t.Fatal("wrong username must still compare password")
	}
	if ok, err := service.Verify(ptr("admin"), ptr("test-secret")); !ok || err != nil {
		t.Fatal("success must reset failures")
	}
	wrong()
	wrong()
	wrong()
	count := codec.matches
	for _, input := range []*string{ptr("test-secret"), ptr("wrong"), ptr("")} {
		ok, err := service.Verify(ptr("admin"), input)
		var locked *LockedError
		if ok || !errors.As(err, &locked) || locked.RetryAfter != 1 ||
			locked.Error() != "登录尝试过于频繁，请 1 秒后再试" {
			t.Fatalf("locked = %t, %v", ok, err)
		}
	}
	if ok, err := service.Verify(nil, ptr("wrong")); ok || err != nil {
		t.Fatal("null must bypass lock check without counting")
	}
	if codec.matches != count {
		t.Fatal("locked/null request performed password check")
	}
	now = now.Add(options.Lockout - time.Nanosecond)
	if _, err := service.Verify(ptr("admin"), ptr("test-secret")); err == nil {
		t.Fatal("unlocked too early")
	}
	now = now.Add(time.Nanosecond)
	if ok, err := service.Verify(ptr("admin"), ptr("test-secret")); !ok || err != nil {
		t.Fatal("expiry must unlock")
	}
	wrong()
	wrong()
	wrong()
	if _, err := service.Verify(ptr("admin"), ptr("wrong")); err == nil {
		t.Fatal("second lock cycle")
	}
}

func TestConcurrentFailuresDoNotOverrunThreshold(t *testing.T) {
	options := enabledOptions()
	options.MaxFailures = 3
	codec := &testCodec{}
	now := time.Now()
	service, err := newService(options, codec, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	var failures, locked atomic.Int32
	var group sync.WaitGroup
	for i := 0; i < 100; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			ok, err := service.Verify(ptr("root"), ptr("wrong"))
			if ok {
				t.Error("wrong password accepted")
			}
			if err == nil {
				failures.Add(1)
			} else {
				var lock *LockedError
				if !errors.As(err, &lock) {
					t.Error(err)
				}
				locked.Add(1)
			}
		}()
	}
	group.Wait()
	if failures.Load() != 3 || locked.Load() != 97 || codec.matches != 3 {
		t.Fatalf("failures=%d locked=%d checks=%d", failures.Load(), locked.Load(), codec.matches)
	}
}

func TestBCryptAgainstJavaOracle(t *testing.T) {
	data, err := os.ReadFile("testdata/java-bcrypt.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Cases []struct {
			Name, Configured, Candidate string
			Hash                        *string
			EncodeError                 bool `json:"encode_error"`
			Matched                     bool
			DefaultPrefix               *string `json:"default_prefix"`
		}
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Cases) < 60 {
		t.Fatal("missing oracle cases")
	}
	codec := bcryptCodec{}
	encoded := map[string]bool{}
	for _, row := range fixture.Cases {
		t.Run(row.Name, func(t *testing.T) {
			if !encoded[row.Configured] {
				generated, err := codec.encode(row.Configured)
				if (err != nil) != row.EncodeError {
					t.Fatalf("encode error = %v", err)
				}
				if err == nil {
					cost, costErr := bcrypt.Cost(generated)
					if row.DefaultPrefix == nil || !strings.HasPrefix(string(generated), *row.DefaultPrefix) || costErr != nil || cost != 10 {
						t.Fatal("default BCrypt cost/version mismatch")
					}
				}
				encoded[row.Configured] = true
			}
			if row.Hash != nil && codec.match(row.Candidate, []byte(*row.Hash)) != row.Matched {
				t.Fatal("Java/Go BCrypt comparison differs")
			}
		})
	}
}
