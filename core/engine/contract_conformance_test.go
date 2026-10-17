package engine

import (
	"testing"

	"github.com/s3ntin3l8/branchdam-mobile/core/contracttest"
)

// TestContractEventPayloadsMatchServer checks the event payloads this
// engine sends against branchDAM's pinned contract fixtures (see
// core/contracttest). core/client's TestContractFixturesAllAccountedFor
// lists these two fixtures as checked here.
func TestContractEventPayloadsMatchServer(t *testing.T) {
	cases := map[string]contracttest.Case{
		"event_edge_attached": {DTO: &edgeAttachedPayload{}},
		"event_node_deleted":  {DTO: &nodeDeletedPayload{}},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) { contracttest.RoundTrip(t, name, c) })
	}
}
