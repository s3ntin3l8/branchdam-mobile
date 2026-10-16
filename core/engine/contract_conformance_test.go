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
		"event_edge_attached": {
			DTO: &edgeAttachedPayload{},
			NotModelled: map[string]string{
				"sourceNodeId": "server-internal row ids; the app addresses nodes by UUID",
				"targetNodeId": "server-internal row ids; the app addresses nodes by UUID",
				"reviewState":  "an agent must never send it (the server rejects agent-supplied review states)",
			},
		},
		"event_node_deleted": {DTO: &nodeDeletedPayload{}},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) { contracttest.RoundTrip(t, name, c) })
	}
}
