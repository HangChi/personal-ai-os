//go:build !darwin && !linux

package app

// 未验收平台不能用虚构磁盘容量报告健康。
func diskSpaceComponent() actuatorComponent { return actuatorComponent{Status: "UNKNOWN"} }
