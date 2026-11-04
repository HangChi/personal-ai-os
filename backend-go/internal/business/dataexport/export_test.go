package dataexport

import (
	"bytes"
	"context"
	"errors"
	"testing"
)

func TestP7BoundedStreamingArrayAndWriterFailures(t *testing.T) {
	var output bytes.Buffer
	w := &Writer{Output: &output}
	pages := 0
	count, e := Array(context.Background(), w, func(page int) ([]int, error) {
		pages++
		if page < 2 {
			return make([]int, BatchSize), nil
		}
		return []int{1}, nil
	}, nil)
	if e != nil || count != 401 || pages != 3 || output.Bytes()[0] != '[' || output.Bytes()[output.Len()-1] != ']' {
		t.Fatal(count, pages, e)
	}
	failure := errors.New("write")
	w = &Writer{Output: failedWriter{failure}}
	_, e = Array(context.Background(), w, func(int) ([]int, error) { return []int{1}, nil }, nil)
	if !errors.Is(e, failure) {
		t.Fatal("write failure ignored", e)
	}
}

type failedWriter struct{ err error }

func (w failedWriter) Write([]byte) (int, error) { return 0, w.err }
