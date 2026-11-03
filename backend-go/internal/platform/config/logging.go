package config

import (
	"log/slog"
	"strings"
)

const BaselineConsolePattern = "%d{HH:mm:ss.SSS} %5p [%X{requestId:------------}] [%15.15t] %-40.40logger{39} : %msg%n"

type LoggingProperties struct {
	Format string
	Level  slog.Level
}

// 日志格式使用Go实际运行信息；不伪造Java线程名/Logback调用者。
func (p *Properties) Logging() (LoggingProperties, error) {
	result := LoggingProperties{Format: "text", Level: slog.LevelInfo}
	if value, found, err := p.Get("logging.structured.format.console"); err != nil {
		return result, err
	} else if found && value != "" {
		if value != "ecs" {
			return result, &propertyError{"logging.structured.format.console", ErrInvalidProperty}
		}
		result.Format = "ecs"
	}
	if value, found, err := p.Get("logging.level.root"); err != nil {
		return result, err
	} else if found {
		switch strings.ToUpper(value) {
		case "TRACE":
			result.Level = slog.Level(-8)
		case "DEBUG":
			result.Level = slog.LevelDebug
		case "INFO":
			result.Level = slog.LevelInfo
		case "WARN":
			result.Level = slog.LevelWarn
		case "ERROR":
			result.Level = slog.LevelError
		case "OFF":
			result.Level = slog.Level(100)
		default:
			return result, &propertyError{"logging.level.root", ErrInvalidProperty}
		}
	}
	return result, nil
}
