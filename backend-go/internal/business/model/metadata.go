package model

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"strconv"
	"strings"
)

// 保留JSON对象原始属性顺序，窗口广度搜索与原Jackson行为一致。
type property struct {
	name string
	node node
}
type node struct {
	object            []property
	array             []node
	scalar            any
	isObject, isArray bool
}

func (n node) at(name string) node {
	for i := len(n.object) - 1; i >= 0; i-- {
		if n.object[i].name == name {
			return n.object[i].node
		}
	}
	return node{}
}
func (n node) text() string { s, _ := n.scalar.(string); return s }
func parseNode(data []byte) (node, error) {
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	count := 0
	n, err := readNode(d, 0, &count)
	if err != nil {
		return node{}, err
	}
	if _, err = d.Token(); err != io.EOF {
		return node{}, errors.New("JSON尾部无效")
	}
	return n, nil
}
func readNode(d *json.Decoder, depth int, count *int) (node, error) {
	*count++
	if depth > 128 || *count > 500000 {
		return node{}, errors.New("JSON超过结构限制")
	}
	t, err := d.Token()
	if err != nil {
		return node{}, err
	}
	switch t {
	case json.Delim('{'):
		n := node{isObject: true}
		for d.More() {
			key, e := d.Token()
			if e != nil {
				return n, e
			}
			v, e := readNode(d, depth+1, count)
			if e != nil {
				return n, e
			}
			n.object = append(n.object, property{key.(string), v})
		}
		_, err = d.Token()
		return n, err
	case json.Delim('['):
		n := node{isArray: true}
		for d.More() {
			v, e := readNode(d, depth+1, count)
			if e != nil {
				return n, e
			}
			n.array = append(n.array, v)
		}
		_, err = d.Token()
		return n, err
	default:
		return node{scalar: t}, nil
	}
}
func windowNumber(n node) *int {
	var raw string
	switch v := n.scalar.(type) {
	case json.Number:
		raw = v.String()
		if f, err := strconv.ParseFloat(raw, 64); err != nil || f != float64(int64(f)) {
			return nil
		} else {
			raw = strconv.FormatInt(int64(f), 10)
		}
	case string:
		if len(v) > 24 || v == "" || v[0] < '0' || v[0] > '9' {
			return nil
		}
		for _, r := range v {
			if (r < '0' || r > '9') && r != ',' {
				return nil
			}
		}
		raw = strings.ReplaceAll(v, ",", "")
	default:
		return nil
	}
	v, err := strconv.Atoi(raw)
	if err != nil || v < 1024 || v > 32*1024*1024 {
		return nil
	}
	return &v
}

var contextFields = []string{"context_length", "context_window", "max_model_len", "max_context_length", "max_context_window", "context_window_tokens", "max_context_window_tokens", "max_position_embeddings", "n_ctx"}
var inputFields = []string{"max_input_tokens", "max_prompt_tokens"}

func derived(n node) bool {
	n = n.at("default_envs")
	a, ok := n.at("max_tokens").scalar.(json.Number)
	b, ok2 := n.at("max_input_tokens").scalar.(json.Number)
	if !ok || !ok2 {
		return false
	}
	x, _ := strconv.ParseFloat(string(a), 64)
	y, _ := strconv.ParseFloat(string(b), 64)
	return int(y) == int(x)-2048
}
func declared(n node, input bool) *int {
	fields := contextFields
	if input {
		fields = inputFields
	}
	for _, f := range fields {
		if v := windowNumber(n.at(f)); v != nil {
			return v
		}
	}
	if !input {
		for _, p := range n.at("model_info").object {
			if strings.HasSuffix(p.name, ".context_length") {
				if v := windowNumber(p.node); v != nil {
					return v
				}
			}
		}
	}
	for _, container := range []string{"extra_info", "data", "model_info", "meta", "metadata", "limits", "capabilities", "top_provider", "config", "details"} {
		child := n.at(container)
		if !child.isObject {
			continue
		}
		type item struct {
			n     node
			depth int
		}
		queue := []item{{child, 0}}
		for len(queue) > 0 {
			v := queue[0]
			queue = queue[1:]
			if v.depth > 4 {
				continue
			}
			for _, p := range v.n.object {
				for _, field := range fields {
					if p.name == field {
						if w := windowNumber(p.node); w != nil {
							return w
						}
					}
				}
				if p.name == "default_envs" && derived(v.n) {
					continue
				}
				if p.node.isObject || p.node.isArray {
					queue = append(queue, item{p.node, v.depth + 1})
				}
			}
			for _, a := range v.n.array {
				if a.isObject || a.isArray {
					queue = append(queue, item{a, v.depth + 1})
				}
			}
		}
	}
	return nil
}
func ReadWindow(data []byte) *int {
	n, err := parseNode(data)
	if err != nil {
		return nil
	}
	if w := declared(n, false); w != nil {
		return w
	}
	return declared(n, true)
}
