package chat

import (
	"bytes"
	"context"
	"errors"
	"os/exec"
	"time"
)

// ExtractPDF 将不可信 PDF 交给独立的 Poppler 进程，绝不构造 shell 命令。
// 固定页数、输入/输出大小和截止时间；解析崩溃、超时不影响 API 进程。
// 部署必须提供 pdftotext（Poppler）；缺失时按附件解析失败处理并提示用户。
func ExtractPDF(ctx context.Context, data []byte) (string, error) {
	if len(data) > 64<<20 || !bytes.HasPrefix(data, []byte("%PDF-")) {
		return "", errors.New("PDF格式无效")
	}
	binary, err := exec.LookPath("pdftotext")
	if err != nil {
		return "", errors.New("未安装PDF文本提取器")
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, binary, "-enc", "UTF-8", "-nopgbrk", "-f", "1", "-l", "100", "-", "-")
	command.Stdin = bytes.NewReader(data)
	command.WaitDelay = time.Second
	output := &boundedPDFText{maximum: 64000}
	command.Stdout = output
	if err = command.Run(); err != nil && !output.full {
		return "", errors.New("PDF文本提取失败")
	}
	if ctx.Err() != nil {
		return "", ctx.Err()
	}
	return strip(stringsUTF8(output.buffer.Bytes())), nil
}

type boundedPDFText struct {
	buffer  bytes.Buffer
	maximum int
	full    bool
}

func (b *boundedPDFText) Write(p []byte) (int, error) {
	n := len(p)
	if b.buffer.Len()+n > b.maximum {
		n = b.maximum - b.buffer.Len()
		if n > 0 {
			b.buffer.Write(p[:n])
		}
		b.full = true
		return n, errors.New("PDF文本达到上限")
	}
	return b.buffer.Write(p)
}
func stringsUTF8(b []byte) string { return string(bytes.ToValidUTF8(b, []byte("�"))) }
