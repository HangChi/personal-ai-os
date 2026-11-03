package api

// Page 区分完整总数和当前页条数，Items 的 JSON 空值必须为 []。
type Page[T any] struct {
	Items []T   `json:"items"`
	Page  int   `json:"page"`
	Size  int   `json:"size"`
	Total int64 `json:"total"`
}

// PageOf 对已经排序的完整结果切片，不重新排序。
func PageOf[T any](all []T, page, size int) Page[T] {
	page, size = max(page, 0), max(size, 1)
	items := make([]T, 0)
	// 先做除法比较再相乘，避免极大 page/size 导致 Go int 溢出。
	if page <= len(all)/size {
		start := page * size
		if start < len(all) {
			count := min(size, len(all)-start)
			items = append(items, all[start:start+count]...)
		}
	}
	return Page[T]{Items: items, Page: page, Size: size, Total: int64(len(all))}
}

// PageOfResult 接收数据库已经分好页的结果，不能再按 page 切片。
func PageOfResult[T any](items []T, page, size int, total int64) Page[T] {
	copyOfItems := append(make([]T, 0, len(items)), items...)
	return Page[T]{Items: copyOfItems, Page: max(page, 0), Size: max(size, 1), Total: total}
}

// OffsetOverflows 对齐 Java 的 Integer.MAX_VALUE 边界，不依赖本机 int 宽度。
func OffsetOverflows(page, size int) bool {
	page, size = max(page, 0), max(size, 0)
	const maxJavaOffset = 1<<31 - 1
	return size > 0 && page > maxJavaOffset/size
}
