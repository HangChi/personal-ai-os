package dataexport

import (
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"personal-ai-os/backend-go/internal/platform/database"
	"slices"
	"time"
)

const BatchSize = 200

type Writer struct {
	Output io.Writer
	Err    error
}

func (w *Writer) Raw(s string) {
	if w.Err == nil {
		_, w.Err = io.WriteString(w.Output, s)
	}
}
func (w *Writer) JSON(v any) {
	if w.Err != nil {
		return
	}
	var b []byte
	b, w.Err = json.Marshal(v)
	if w.Err == nil {
		_, w.Err = w.Output.Write(b)
	}
}
func (w *Writer) Flush() {
	if w.Err == nil {
		if f, ok := w.Output.(interface{ Flush() }); ok {
			f.Flush()
		}
	}
}

type Contributor interface {
	Key() string
	Write(context.Context, *sql.Tx, *Writer) (int, error)
}

func Array[T any](ctx context.Context, w *Writer, fetch func(int) ([]T, error), emit func(T) error) (int, error) {
	w.Raw("[")
	count := 0
	for page := 0; ; page++ {
		if e := ctx.Err(); e != nil {
			return count, e
		}
		batch, e := fetch(page)
		if e != nil {
			return count, e
		}
		for _, v := range batch {
			if count > 0 {
				w.Raw(",")
			}
			if emit == nil {
				w.JSON(v)
			} else if e := emit(v); e != nil {
				return count, e
			}
			if w.Err != nil {
				return count, w.Err
			}
			count++
		}
		w.Flush()
		if len(batch) < BatchSize {
			break
		}
	}
	w.Raw("]")
	return count, w.Err
}

type Service struct {
	Pool          *database.Pool
	Name, Version string
	Contributors  []Contributor
}

func (s *Service) Write(ctx context.Context, out io.Writer) error {
	return s.Pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error {
		w := &Writer{Output: out}
		w.Raw(`{"application":`)
		w.JSON(s.Name)
		w.Raw(`,"version":`)
		w.JSON(s.Version)
		w.Raw(`,"formatVersion":"1","exportedAt":`)
		w.JSON(time.Now().UTC())
		contributors := append([]Contributor{}, s.Contributors...)
		slices.SortFunc(contributors, func(a, b Contributor) int {
			if a.Key() < b.Key() {
				return -1
			}
			if a.Key() > b.Key() {
				return 1
			}
			return 0
		})
		counts := map[string]int{}
		for _, c := range contributors {
			w.Raw(",")
			w.JSON(c.Key())
			w.Raw(":")
			n, e := c.Write(ctx, tx, w)
			if e != nil {
				return e
			}
			counts[c.Key()] = n
		}
		w.Raw(`,"counts":`)
		w.JSON(counts)
		w.Raw("}")
		return w.Err
	})
}
