package client

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// TestDefaultClientsRefuseTLSDowngrade: an https server that redirects to
// http must not get the API key sent in cleartext. net/http would copy
// X-API-Key onto the redirected request, so the default clients refuse the
// redirect and the plain-http server is never contacted.
func TestDefaultClientsRefuseTLSDowngrade(t *testing.T) {
	var plainHits atomic.Int32
	plain := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		plainHits.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer plain.Close()

	secure := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, plain.URL+r.URL.Path, http.StatusTemporaryRedirect)
	}))
	defer secure.Close()

	c := New(Config{BaseURL: secure.URL, APIKey: "secret-key", AgentID: "pixel"})
	// Trust the test server's certificate but keep the default clients'
	// redirect policy, which is what is under test.
	c.httpClient.Transport = secure.Client().Transport
	c.uploadClient.Transport = secure.Client().Transport

	if _, err := c.Handshake(context.Background(), ""); err == nil {
		t.Fatal("Handshake followed an https->http redirect")
	}
	if _, err := c.UploadStream(context.Background(), strings.NewReader("x"), 1, "a.jpg", UploadOptions{}); err == nil {
		t.Fatal("UploadStream followed an https->http redirect")
	}
	if n := plainHits.Load(); n != 0 {
		t.Fatalf("plain-http server received %d requests", n)
	}

	// Control: with net/http's default redirect policy the same setup
	// does reach the plain-http server, so the refusal above is the
	// redirect policy at work and not a TLS or setup failure.
	c.httpClient.CheckRedirect = nil
	_, _ = c.Handshake(context.Background(), "")
	if plainHits.Load() == 0 {
		t.Fatal("control: the default redirect policy did not follow the redirect")
	}
}

func TestRefuseTLSDowngrade(t *testing.T) {
	req := func(u string) *http.Request {
		r, err := http.NewRequest(http.MethodGet, u, nil)
		if err != nil {
			t.Fatal(err)
		}
		return r
	}
	if err := refuseTLSDowngrade(req("https://b.example/x"), []*http.Request{req("https://a.example/x")}); err != nil {
		t.Errorf("https->https refused: %v", err)
	}
	if err := refuseTLSDowngrade(req("http://b.example/x"), []*http.Request{req("http://a.example/x")}); err != nil {
		t.Errorf("http->http (debug dev host) refused: %v", err)
	}
	if err := refuseTLSDowngrade(req("http://a.example/x"), []*http.Request{req("https://a.example/x")}); err == nil {
		t.Error("https->http allowed")
	}
	// A downgrade anywhere in the chain counts.
	via := []*http.Request{req("https://a.example/1"), req("https://a.example/2")}
	if err := refuseTLSDowngrade(req("http://a.example/3"), via); err == nil {
		t.Error("https->https->http allowed")
	}
	long := make([]*http.Request, maxRedirects)
	for i := range long {
		long[i] = req("https://a.example/")
	}
	if err := refuseTLSDowngrade(req("https://a.example/"), long); err == nil {
		t.Error("redirect loop not stopped")
	}
}
