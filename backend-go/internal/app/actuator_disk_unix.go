//go:build darwin || linux

package app

import (
	"os"
	"syscall"
)

func diskSpaceComponent() actuatorComponent {
	var stat syscall.Statfs_t
	path, err := os.Getwd()
	if err != nil || syscall.Statfs(".", &stat) != nil {
		return actuatorComponent{Status: "DOWN"}
	}
	const threshold = 10 * 1024 * 1024
	free := uint64(stat.Bavail) * uint64(stat.Bsize)
	state := "UP"
	if free < threshold {
		state = "DOWN"
	}
	return actuatorComponent{Status: state, Details: map[string]any{"total": uint64(stat.Blocks) * uint64(stat.Bsize), "free": free, "threshold": threshold, "path": path + string(os.PathSeparator) + ".", "exists": true}}
}
