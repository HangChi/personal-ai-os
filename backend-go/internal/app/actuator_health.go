package app

import (
	"context"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

func (f *Foundation) actuatorSnapshot(properties config.ActuatorProperties, clients []*redisclient.Client) func(context.Context) actuatorHealth {
	return func(ctx context.Context) actuatorHealth {
		ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		defer cancel()
		state := defaultActuatorHealth(f.health.CurrentStatus(ctx).Status == "UP", properties)
		db := state.Components["db"]
		if db.Status == "UP" {
			db.Details = map[string]any{"database": "PostgreSQL", "validationQuery": "SELECT 1"}
		}
		state.Components["db"] = db
		if properties.DiskSpace {
			component := diskSpaceComponent()
			state.Components["diskSpace"] = component
			if component.Status != "UP" {
				state.Status = "DOWN"
			}
		}
		for index, client := range clients {
			if client == nil {
				continue
			}
			component := actuatorComponent{Status: "UP"}
			if err := client.Ping(ctx); err != nil {
				component.Status = "DOWN"
				state.Status = "DOWN"
			}
			name := "redisSession"
			if index == 1 {
				name = "redisRateLimit"
			}
			state.Components[name] = component
		}
		return state
	}
}
