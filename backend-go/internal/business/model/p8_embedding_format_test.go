package model

import (
	"testing"
	"time"
)

func TestP8EmbeddingDocumentMetadataIsProviderSpecificAndSurvivesCache(t *testing.T) {
	for _, protocol := range []Protocol{OpenAI, Ollama} {
		client := &EmbeddingClient{protocol: protocol}
		cache, err := NewCachedEmbedding(client, "test", true, 10, time.Minute, nil)
		if err != nil {
			t.Fatal(err)
		}
		if client.EmbedsDocumentMetadata() != (protocol == OpenAI) || cache.EmbedsDocumentMetadata() != (protocol == OpenAI) {
			t.Fatal("provider formatting policy was lost in cache", protocol)
		}
	}
}
