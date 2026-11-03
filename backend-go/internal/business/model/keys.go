package model

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"strings"
)

const keyPrefix = "{aesgcm:v1}"
const keyAAD = "personal-ai-os:model-api-key:v1"

var (
	errMasterKey     = errors.New("MODEL_KEY_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节随机密钥")
	errKeyEncryption = errors.New("模型密钥加密失败")
	errKeyDecryption = errors.New("模型密钥解密失败，请检查主密钥与备份完整性")
	errKeyRequired   = errors.New("读取加密模型配置需要 MODEL_KEY_ENCRYPTION_KEY")
)

// KeyCodec 与 ModelKeyConverter 使用相同前缀、AES-256-GCM、12字节IV和AAD。
// 主密钥不与数据一同保存；未配置时保留旧明文，但拒绝读取加密值。
type KeyCodec struct{ aead cipher.AEAD }

func (KeyCodec) String() string               { return "ModelKeyCodec{redacted}" }
func (c KeyCodec) GoString() string           { return c.String() }
func (KeyCodec) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

func NewKeyCodec(encoded string) (*KeyCodec, error) {
	if strip(encoded) == "" {
		return &KeyCodec{}, nil
	}
	key, err := decodeBase64(encoded)
	if err != nil || len(key) != 32 {
		return nil, errMasterKey
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, errMasterKey
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, errMasterKey
	}
	return &KeyCodec{aead: aead}, nil
}

func (c *KeyCodec) Encrypt(plain *string) (*string, error) {
	if plain == nil || c.aead == nil || *plain == "" {
		return copyPointer(plain), nil
	}
	iv := make([]byte, c.aead.NonceSize())
	if _, err := rand.Read(iv); err != nil {
		return nil, errKeyEncryption
	}
	data := c.aead.Seal(iv, iv, []byte(*plain), []byte(keyAAD))
	encoded := keyPrefix + base64.StdEncoding.EncodeToString(data)
	return &encoded, nil
}

func (c *KeyCodec) Decrypt(stored *string) (*string, error) {
	if stored == nil || !strings.HasPrefix(*stored, keyPrefix) {
		return copyPointer(stored), nil
	}
	if c.aead == nil {
		return nil, errKeyRequired
	}
	data, err := decodeBase64(strings.TrimPrefix(*stored, keyPrefix))
	if err != nil || len(data) < c.aead.NonceSize()+c.aead.Overhead() {
		return nil, errKeyDecryption
	}
	plain, err := c.aead.Open(nil, data[:c.aead.NonceSize()], data[c.aead.NonceSize():], []byte(keyAAD))
	if err != nil {
		return nil, errKeyDecryption
	}
	value := strings.ToValidUTF8(string(plain), "\ufffd")
	return &value, nil
}

func decodeBase64(value string) ([]byte, error) {
	// Java Basic decoder 接受省略padding，但拒绝换行；Go默认解码器会忽略CR/LF。
	if strings.ContainsAny(value, "\r\n") {
		return nil, errMasterKey
	}
	if !strings.Contains(value, "=") {
		return base64.RawStdEncoding.DecodeString(value)
	}
	return base64.StdEncoding.DecodeString(value)
}
