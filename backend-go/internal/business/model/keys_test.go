package model

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func fixtureKey(t *testing.T, fill byte) *KeyCodec {
	t.Helper()
	c, err := NewKeyCodec(base64.StdEncoding.EncodeToString([]byte(strings.Repeat(string(fill), 32))))
	if err != nil {
		t.Fatal(err)
	}
	return c
}

// 来自 ModelKeyConverterTest：随机IV、认证失败、旧明文、禁用、Unicode长密钥与错误脱敏。
func TestKeyCodecCompatibilityAndAuthentication(t *testing.T) {
	enabled := fixtureKey(t, 1)
	other := fixtureKey(t, 2)
	disabled, err := NewKeyCodec("")
	if err != nil {
		t.Fatal(err)
	}
	for _, plain := range []string{"fixture-only-key", strings.Repeat("a", 500), strings.Repeat("密", 500)} {
		first, err := enabled.Encrypt(&plain)
		if err != nil {
			t.Fatal(err)
		}
		second, err := enabled.Encrypt(&plain)
		if err != nil {
			t.Fatal(err)
		}
		if first == nil || second == nil || *first == *second || !strings.HasPrefix(*first, keyPrefix) || strings.Contains(*first, plain) || len(*first) >= 3000 {
			t.Fatal("ciphertext framing/randomness mismatch")
		}
		decoded, err := enabled.Decrypt(first)
		if err != nil || decoded == nil || *decoded != plain {
			t.Fatal("roundtrip failed")
		}
		for _, codec := range []*KeyCodec{other, disabled} {
			if _, err := codec.Decrypt(first); err == nil {
				t.Fatal("unauthorized decryption")
			}
		}
		payload, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(*first, keyPrefix))
		if err != nil {
			t.Fatal(err)
		}
		payload[len(payload)-1] ^= 1
		if _, err := enabled.Decrypt(ptr(keyPrefix + base64.StdEncoding.EncodeToString(payload))); err == nil {
			t.Fatal("tamper accepted")
		}
	}
	for _, codec := range []*KeyCodec{enabled, disabled} {
		for _, plain := range []*string{nil, ptr("")} {
			for _, op := range []func(*string) (*string, error){codec.Encrypt, codec.Decrypt} {
				out, err := op(plain)
				if err != nil || plain == nil && out != nil || plain != nil && (out == nil || *out != "") {
					t.Fatal("empty/null changed")
				}
			}
		}
		out, err := codec.Decrypt(ptr("legacy-fixture-key"))
		if err != nil || *out != "legacy-fixture-key" {
			t.Fatal("legacy plaintext unreadable")
		}
	}
	out, err := disabled.Encrypt(ptr("legacy-fixture-key"))
	if err != nil || *out != "legacy-fixture-key" {
		t.Fatal("disabled encryption changed legacy data")
	}
	for _, bad := range []string{"not-base64", "YWJj", "AQ==", strings.Repeat("A", 43) + "\n"} {
		if _, err := NewKeyCodec(bad); err == nil || strings.Contains(err.Error(), bad) {
			t.Fatal("invalid master key not safely rejected")
		}
	}
	for _, bad := range []string{keyPrefix + "???", keyPrefix + "YWJj", keyPrefix + strings.Repeat("A", 40)} {
		if _, err := enabled.Decrypt(&bad); err == nil || strings.Contains(err.Error(), bad) {
			t.Fatal("invalid payload not safely rejected")
		}
	}
	for _, codec := range []*KeyCodec{enabled, disabled} {
		b, err := json.Marshal(codec)
		if err != nil || string(b) != `{"redacted":true}` || strings.Contains(fmt.Sprintf("%+v %#v", codec, codec), "AEAD") {
			t.Fatal("master key boundary failed")
		}
	}
}
