package api

import (
	"encoding/json"
	"errors"
	"mime"
	"strings"

	"golang.org/x/text/encoding"
	"golang.org/x/text/encoding/ianaindex"
	"golang.org/x/text/encoding/unicode"
	"golang.org/x/text/encoding/unicode/utf32"
)

// CanonicalCharset验证已知字符集名称，不把有效但不受Jackson JSON写入器支持的字符集当成未知。
func CanonicalCharset(name string) (string, error) {
	if name == "" {
		return "UTF-8", nil
	}
	switch strings.ToUpper(name) {
	case "UTF-32", "UTF_32":
		return "UTF-32", nil
	case "UTF-32BE", "UTF_32BE":
		return "UTF-32BE", nil
	case "UTF-32LE", "UTF_32LE":
		return "UTF-32LE", nil
	}
	codec, err := ianaindex.MIME.Encoding(name)
	if err != nil || codec == nil {
		return "", errors.New("字符集名称无效")
	}
	return ianaindex.MIME.Name(codec)
}

// JSONEncoding对应Jackson的五种JsonEncoding；UTF-16/旧单字节字符集不是合法JSON写入目标。
func JSONEncoding(name string) (encoding.Encoding, error) {
	canonical, err := CanonicalCharset(name)
	if err != nil {
		return nil, err
	}
	switch canonical {
	case "UTF-8":
		return unicode.UTF8, nil
	case "UTF-16BE":
		return unicode.UTF16(unicode.BigEndian, unicode.IgnoreBOM), nil
	case "UTF-16LE":
		return unicode.UTF16(unicode.LittleEndian, unicode.IgnoreBOM), nil
	case "UTF-32BE":
		return utf32.UTF32(utf32.BigEndian, utf32.IgnoreBOM), nil
	case "UTF-32LE":
		return utf32.UTF32(utf32.LittleEndian, utf32.IgnoreBOM), nil
	default:
		return nil, errors.New("JSON响应字符集不受支持")
	}
}

// MarshalJSONMediaType在提交响应头前完成序列化与实际字节编码，绝不只改charset声明。
func MarshalJSONMediaType(value any, contentType string) ([]byte, error) {
	_, parameters, err := mime.ParseMediaType(contentType)
	if err != nil {
		return nil, err
	}
	codec, err := JSONEncoding(parameters["charset"])
	if err != nil {
		return nil, err
	}
	data, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	return codec.NewEncoder().Bytes(data)
}
