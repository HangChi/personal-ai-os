package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"time"
)

// Response 保留 data 字段；失败或空成功响应必须编码为 data:null。
type Response[T any] struct {
	Code      ErrorCode `json:"code"`
	Message   string    `json:"message"`
	Data      T         `json:"data"`
	Timestamp time.Time `json:"timestamp"`
}

func OK[T any](data T) Response[T] {
	return Response[T]{Code: Success, Message: Success.Message(), Data: data, Timestamp: time.Now().UTC()}
}

func Failure(code ErrorCode) Response[any] {
	return FailureMessage(code, code.Message())
}

// FailureMessage 的调用方只能传入经过脱敏的业务文案。
func FailureMessage(code ErrorCode, message string) Response[any] {
	return Response[any]{Code: code, Message: message, Data: nil, Timestamp: time.Now().UTC()}
}

func (response Response[T]) IsSuccess() bool { return response.Code == Success }

// WriteJSON 先序列化再提交响应头，防止序列化失败留下“成功但半截”的响应。
// 仅用于普通 JSON；SSE、导出和附件后续使用各自的协议写入器。
func WriteJSON(w http.ResponseWriter, status int, value any) error {
	return writeJSON(w, status, value, "application/json;charset=UTF-8")
}

// WriteControllerJSON 对齐 MVC/Jackson 的默认媒体类型；安全过滤器继续使用 WriteJSON。
func WriteControllerJSON(w http.ResponseWriter, status int, value any) error {
	return writeJSON(w, status, value, "application/json")
}

func writeJSON(w http.ResponseWriter, status int, value any, contentType string) error {
	data, marshalErr := json.Marshal(value)
	if marshalErr != nil {
		status = http.StatusInternalServerError
		data, _ = json.Marshal(Failure(InternalError))
	}
	w.Header().Set("Content-Type", contentType)
	w.WriteHeader(status)
	_, writeErr := w.Write(append(data, '\n'))
	return errors.Join(marshalErr, writeErr)
}

func WriteError(w http.ResponseWriter, code ErrorCode) error {
	return WriteJSON(w, code.HTTPStatus(), Failure(code))
}
