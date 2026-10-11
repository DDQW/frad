package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/libp2p/go-libp2p/core/peer"

	p2pnode "frad.local/p2p-go/node"
)

const (
	// officialListTimeout bounds one fetch of the official list, so a slow or
	// hostile mirror can't stall the refresh loop.
	officialListTimeout = 10 * time.Second
	// maxOfficialListBytes is far above what the crawler ever writes (at most
	// 100 entries plus a header), and small enough that a hijacked mirror
	// can't make every node download something big.
	maxOfficialListBytes = 64 << 10
)

// refreshOfficialList fetches the official node list from the first mirror
// that answers and queues its entries as candidates. They get the same
// liveness check as anything else: the official list is a convenient source
// of addresses, not an authority this node takes on faith.
func (s *server) refreshOfficialList(ctx context.Context) {
	for _, url := range s.cfg.officialURLs {
		infos, err := fetchNodeList(ctx, s.http, url, s.cfg.allowPrivate)
		if err != nil {
			s.cfg.logf("official node list: %v", err)
			continue
		}
		s.store.addCandidates(fromCurated, url, infos)
		s.cfg.logf("official node list: %d nodes from %s", len(infos), url)
		return
	}
}

// fetchNodeList downloads and parses a node list (see p2pnode.ParseNodeList);
// a body over maxOfficialListBytes is rejected rather than truncated mid-line.
func fetchNodeList(ctx context.Context, client *http.Client, url string, allowPrivate bool) ([]peer.AddrInfo, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s: HTTP %s", url, resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxOfficialListBytes+1))
	if err != nil {
		return nil, fmt.Errorf("%s: %w", url, err)
	}
	if len(body) > maxOfficialListBytes {
		return nil, fmt.Errorf("%s: larger than %d bytes", url, maxOfficialListBytes)
	}
	return p2pnode.ParseNodeList(string(body), allowPrivate), nil
}
