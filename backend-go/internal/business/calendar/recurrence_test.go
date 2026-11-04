package calendar

import (
	"encoding/json"
	"os"
	"slices"
	"testing"
	"time"
)

func TestOccurrencesDSTAndMonthlyAnchor(t *testing.T) {
	zone, err := time.LoadLocation("America/New_York")
	if err != nil {
		t.Fatal(err)
	}
	start := time.Date(2024, 1, 31, 9, 0, 0, 0, zone)
	to := time.Date(2024, 3, 31, 9, 0, 0, 0, zone)
	monthly := Occurrences(Monthly, start, &to, start, to.AddDate(0, 1, 0), zone)
	if len(monthly) != 3 || monthly[1].In(zone).Day() != 29 || !monthly[2].Equal(to) || monthly[2].Hour() != 13 {
		t.Fatalf("monthly = %v", monthly)
	}
	start = time.Date(2024, 3, 9, 2, 30, 0, 0, zone)
	daily := Occurrences(Daily, start, nil, start, start.Add(48*time.Hour), zone)
	if len(daily) != 3 || daily[1].In(zone).Hour() != 3 || daily[2].In(zone).Hour() != 3 {
		t.Fatalf("daily = %v", daily)
	}
}

func TestJavaRecurrenceOracle(t *testing.T) {
	data, err := os.ReadFile("testdata/java-recurrence.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Cases []struct {
			Zone            string
			Rule            Recurrence
			Start, From, To time.Time
			Until           *time.Time
			Expected        []time.Time
		}
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	for i, test := range fixture.Cases {
		zone, err := time.LoadLocation(test.Zone)
		if err != nil {
			t.Fatal(err)
		}
		got := Occurrences(test.Rule, test.Start, test.Until, test.From, test.To, zone)
		if !slices.EqualFunc(got, test.Expected, time.Time.Equal) {
			t.Errorf("case %d %s %s from %v: got %v, want %v", i, test.Zone, test.Rule, test.From, got, test.Expected)
		}
	}
}
