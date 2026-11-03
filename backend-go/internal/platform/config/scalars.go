package config

import (
	"errors"
	"math/big"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var (
	ErrScalarOverflow   = errors.New("配置数值超出 Go 可表示范围")
	errInvalidDuration  = errors.New("配置必须是有效 Spring 时长")
	errInvalidDataSize  = errors.New("配置必须是有效 Spring 数据大小")
	simpleScalarPattern = regexp.MustCompile(`^([+-]?[0-9]+)([a-zA-Z]{0,2})$`)
	isoDurationPattern  = regexp.MustCompile(`^([+-]?)[Pp](?:([+-]?[0-9]+)[Dd])?(?:[Tt](?:([+-]?[0-9]+)[Hh])?(?:([+-]?[0-9]+)[Mm])?(?:([+-]?[0-9]+)(?:[.,]([0-9]{0,9}))?[Ss])?)?$`)
)

// ParseDuration 对齐未注解的Spring ApplicationConversionService：无单位为毫秒，
// 简写为整数ns/us/ms/s/m/h/d（不接受Go的1.5s/1h30m），或Java ISO-8601。
// 空字符串保持Java null，其余不trim。正值等业务限制由对应配置类负责。
// Java Duration范围大于Go；无法表示为int64纳秒时明确拒绝，绝不溢出或截断。
func ParseDuration(source string) (*time.Duration, error) {
	nanos, err := durationNanos(source)
	if err != nil || nanos == nil {
		return nil, err
	}
	if !nanos.IsInt64() {
		return nil, ErrScalarOverflow
	}
	value := time.Duration(nanos.Int64())
	return &value, nil
}

// durationNanos保留Java完整Duration范围，供先归一化非正值的record使用。
// 对外ParseDuration仍严格拒绝超出Go范围的原始时长。
func durationNanos(source string) (*big.Int, error) {
	if source == "" {
		return nil, nil
	}
	var nanos *big.Int
	if match := simpleScalarPattern.FindStringSubmatch(source); match != nil {
		amount, err := strconv.ParseInt(match[1], 10, 64)
		if err != nil {
			return nil, errInvalidDuration
		}
		var unit int64
		switch strings.ToLower(match[2]) {
		case "ns":
			unit = 1
		case "us":
			unit = 1_000
		case "", "ms":
			unit = 1_000_000
		case "s":
			unit = 1_000_000_000
		case "m":
			unit = 60_000_000_000
		case "h":
			unit = 3_600_000_000_000
		case "d":
			unit = 86_400_000_000_000
		default:
			return nil, errInvalidDuration
		}
		if unit > 1_000_000_000 && !new(big.Int).Mul(big.NewInt(amount), big.NewInt(unit/1_000_000_000)).IsInt64() {
			return nil, errInvalidDuration
		}
		nanos = new(big.Int).Mul(big.NewInt(amount), big.NewInt(unit))
	} else {
		var err error
		nanos, err = parseISODuration(source)
		if err != nil {
			return nil, err
		}
	}
	// Java Duration存储long秒与0..999999999纳秒；归一化前仍须保留其溢出边界。
	minimum := new(big.Int).Mul(big.NewInt(-1<<63), big.NewInt(1_000_000_000))
	maximum := new(big.Int).Add(new(big.Int).Mul(big.NewInt(1<<63-1), big.NewInt(1_000_000_000)), big.NewInt(999_999_999))
	if nanos.Cmp(minimum) < 0 || nanos.Cmp(maximum) > 0 {
		return nil, errInvalidDuration
	}
	return nanos, nil
}

func parseISODuration(source string) (*big.Int, error) {
	match := isoDurationPattern.FindStringSubmatch(source)
	if match == nil {
		return nil, errInvalidDuration
	}
	if match[2] == "" && match[3] == "" && match[4] == "" && match[5] == "" {
		return nil, errInvalidDuration
	}
	if strings.ContainsAny(source, "Tt") && match[3] == "" && match[4] == "" && match[5] == "" {
		return nil, errInvalidDuration
	}
	seconds := new(big.Int)
	parts := make([]*big.Int, 4)
	for i, multiplier := range []int64{86400, 3600, 60, 1} {
		parts[i] = new(big.Int)
		if match[i+2] == "" {
			continue
		}
		value, err := strconv.ParseInt(match[i+2], 10, 64)
		if err != nil {
			return nil, errInvalidDuration
		}
		part := new(big.Int).Mul(big.NewInt(value), big.NewInt(multiplier))
		// Java先以long计算每个分量，不能用大数相消掩盖分量溢出。
		if !part.IsInt64() {
			return nil, errInvalidDuration
		}
		parts[i] = part
	}
	// Duration.create为 days + (hours + (minutes + seconds))，顺序会改变溢出边界。
	for i := len(parts) - 1; i >= 0; i-- {
		seconds.Add(seconds, parts[i])
		if !seconds.IsInt64() {
			return nil, errInvalidDuration
		}
	}
	nanos := new(big.Int).Mul(seconds, big.NewInt(1_000_000_000))
	if fraction := match[6]; fraction != "" {
		value, err := strconv.ParseInt(fraction+strings.Repeat("0", 9-len(fraction)), 10, 64)
		if err != nil {
			return nil, errInvalidDuration
		}
		if strings.HasPrefix(match[5], "-") {
			value = -value
		}
		nanos.Add(nanos, big.NewInt(value))
	}
	if match[1] == "-" {
		nanos.Neg(nanos)
	}
	return nanos, nil
}

// ParseDataSize 对齐Spring DataSize：B/KB/MB/GB/TB为二进制倍率，后缀大小写敏感，
// 无单位为byte。移除全部Java Character.isWhitespace（包括中间），保留NBSP等。
// 空源字符串为null；纯空白不是null。负值是合法转换结果，由使用方决定是否允许。
func ParseDataSize(source string) (*int64, error) {
	if source == "" {
		return nil, nil
	}
	value := strings.Map(func(r rune) rune {
		if javaWhitespace(r) {
			return -1
		}
		return r
	}, source)
	match := simpleScalarPattern.FindStringSubmatch(value)
	if match == nil {
		return nil, errInvalidDataSize
	}
	amount, err := strconv.ParseInt(match[1], 10, 64)
	if err != nil {
		return nil, errInvalidDataSize
	}
	var unit int64
	switch match[2] {
	case "", "B":
		unit = 1
	case "KB":
		unit = 1 << 10
	case "MB":
		unit = 1 << 20
	case "GB":
		unit = 1 << 30
	case "TB":
		unit = 1 << 40
	default:
		return nil, errInvalidDataSize
	}
	bytes := new(big.Int).Mul(big.NewInt(amount), big.NewInt(unit))
	if !bytes.IsInt64() {
		return nil, ErrScalarOverflow
	}
	result := bytes.Int64()
	return &result, nil
}
