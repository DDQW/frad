package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestFetchNodeList(t *testing.T) {
	id := randomID(t).String()
	mux := http.NewServeMux()
	mux.HandleFunc("/ok", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(strings.Join([]string{
			"# FRAD official node list",
			"/ip4/93.184.216.34/tcp/4001/p2p/" + id,
			"/ip4/10.0.0.1/tcp/4001/p2p/" + id, // private: dropped
			"not a multiaddr",
			"",
		}, "\n")))
	})
	mux.HandleFunc("/huge", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("# " + strings.Repeat("x", maxOfficialListBytes)))
	})
	mux.HandleFunc("/slow", func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
		case <-time.After(5 * time.Second):
		}
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()
	client := &http.Client{Timeout: officialListTimeout}
	ctx := context.Background()

	infos, err := fetchNodeList(ctx, client, srv.URL+"/ok", false)
	if err != nil || len(infos) != 1 || len(infos[0].Addrs) != 1 || infos[0].ID.String() != id {
		t.Fatalf("got %v, %v", infos, err)
	}
	if _, err := fetchNodeList(ctx, client, srv.URL+"/huge", false); err == nil {
		t.Error("expected an oversized list to be rejected")
	}
	if _, err := fetchNodeList(ctx, client, srv.URL+"/missing", false); err == nil {
		t.Error("expected an HTTP error status to be an error")
	}
	if _, err := fetchNodeList(ctx, &http.Client{Timeout: 100 * time.Millisecond}, srv.URL+"/slow", false); err == nil {
		t.Error("expected a timeout")
	}
}

func TestRefreshOfficialListFallsBackToMirror(t *testing.T) {
	listed := newTestServer(t, nil)
	mux := http.NewServeMux()
	mux.HandleFunc("/down", func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
	})
	mux.HandleFunc("/mirror", func(w http.ResponseWriter, _ *http.Request) {
		for _, addr := range listed.h.Addrs() {
			_, _ = w.Write([]byte(addr.String() + "/p2p/" + listed.h.ID().String() + "\n"))
		}
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	s := newTestServer(t, func(cfg *config) { cfg.officialURLs = []string{srv.URL + "/down", srv.URL + "/mirror"} })
	s.refreshOfficialList(context.Background())
	if !s.store.isCandidate(listed.h.ID()) {
		t.Fatal("the mirror's entries didn't become candidates")
	}
	if s.store.isKnown(listed.h.ID()) {
		t.Fatal("the official list must not bypass the liveness check")
	}
}
