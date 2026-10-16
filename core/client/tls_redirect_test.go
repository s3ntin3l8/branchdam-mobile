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

func TestRefuseUnsafeRedirect(t *testing.T) {
	req := func(u string) *http.Request {
		r, err := http.NewRequest(http.MethodGet, u, nil)
		if err != nil {
			t.Fatal(err)
		}
		return r
	}
	if err := refuseUnsafeRedirect(req("https://a.example/y"), []*http.Request{req("https://a.example/x")}); err != nil {
		t.Errorf("same-host https->https refused: %v", err)
	}
	if err := refuseUnsafeRedirect(req("https://A.example/y"), []*http.Request{req("https://a.example/x")}); err != nil {
		t.Errorf("same host in another case refused: %v", err)
	}
	if err := refuseUnsafeRedirect(req("http://a.example/y"), []*http.Request{req("http://a.example/x")}); err != nil {
		t.Errorf("same-host http->http (debug dev host) refused: %v", err)
	}
	// net/http would copy X-API-Key to any other host.
	if err := refuseUnsafeRedirect(req("https://b.example/x"), []*http.Request{req("https://a.example/x")}); err == nil {
		t.Error("https->https to another host allowed")
	}
	if err := refuseUnsafeRedirect(req("https://a.example:8443/x"), []*http.Request{req("https://a.example/x")}); err == nil {
		t.Error("redirect to another port allowed")
	}
	if err := refuseUnsafeRedirect(req("http://a.example/x"), []*http.Request{req("https://a.example/x")}); err == nil {
		t.Error("https->http allowed")
	}
	// An explicit default port is the same host as an omitted one.
	if err := refuseUnsafeRedirect(req("https://h/x"), []*http.Request{req("https://h:443/x")}); err != nil {
		t.Errorf("https :443 -> no port refused: %v", err)
	}
	// A downgrade to the default http port is still a downgrade.
	if err := refuseUnsafeRedirect(req("http://a.example:80/x"), []*http.Request{req("https://a.example/x")}); err == nil {
		t.Error("https->http :80 allowed")
	}
	// A port that is the default only for the other scheme is a real port.
	if err := refuseUnsafeRedirect(req("https://a.example:80/x"), []*http.Request{req("https://a.example/x")}); err == nil {
		t.Error("https -> https :80 allowed")
	}
	if err := refuseUnsafeRedirect(req("http://a.example:443/x"), []*http.Request{req("http://a.example/x")}); err == nil {
		t.Error("http -> http :443 allowed")
	}
	// A downgrade anywhere in the chain counts.
	via := []*http.Request{req("https://a.example/1"), req("https://a.example/2")}
	if err := refuseUnsafeRedirect(req("http://a.example/3"), via); err == nil {
		t.Error("https->https->http allowed")
	}
	// So does a hop to another host after a same-host one.
	if err := refuseUnsafeRedirect(req("https://b.example/3"), via); err == nil {
		t.Error("https->https->other host allowed")
	}
	long := make([]*http.Request, maxRedirects)
	for i := range long {
		long[i] = req("https://a.example/")
	}
	if err := refuseUnsafeRedirect(req("https://a.example/"), long); err == nil {
		t.Error("redirect loop not stopped")
	}
}

// TestRefuseUnsafeRedirectDefaultPorts: an explicit default port (443 for
// https, 80 for http) compares equal to an omitted one, in either direction
// and case-insensitively, while any other port is still another host.
func TestRefuseUnsafeRedirectDefaultPorts(t *testing.T) {
	req := func(u string) *http.Request {
		r, err := http.NewRequest(http.MethodGet, u, nil)
		if err != nil {
			t.Fatal(err)
		}
		return r
	}
	same := []struct{ from, to string }{
		{"https://h/x", "https://h:443/x"},
		{"https://h:443/x", "https://h/x"},
		{"http://h/x", "http://h:80/x"},
		{"http://h:80/x", "http://h/x"},
		{"https://A.Example/x", "https://a.example:443/x"},
		{"HTTPS://a.example:443/x", "https://A.EXAMPLE/x"},
		{"https://[::1]/x", "https://[::1]:443/x"},
		{"https://h:8443/x", "https://H:8443/y"},
	}
	for _, c := range same {
		if err := refuseUnsafeRedirect(req(c.to), []*http.Request{req(c.from)}); err != nil {
			t.Errorf("%s -> %s refused: %v", c.from, c.to, err)
		}
	}
	other := []struct{ from, to string }{
		{"https://h/x", "https://h:8443/x"},
		{"https://h:443/x", "https://h:444/x"},
		{"https://h/x", "https://h:80/x"},
		{"http://h/x", "http://h:443/x"},
		{"https://[::1]/x", "https://[::1]:8443/x"},
		{"https://[::1]:443/x", "https://[::2]/x"},
		{"https://h:443/x", "https://other:443/x"},
	}
	for _, c := range other {
		if err := refuseUnsafeRedirect(req(c.to), []*http.Request{req(c.from)}); err == nil {
			t.Errorf("%s -> %s allowed", c.from, c.to)
		}
	}
}
