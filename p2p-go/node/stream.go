package node

import (
	"github.com/libp2p/go-libp2p/core/network"
)

// Stream wraps a libp2p network.Stream for gomobile: plain byte in/out, since
// gomobile bind can't cross io.Reader/io.Writer. Read takes an explicit max
// length and returns however many bytes were available up to it — the Kotlin
// side (WideRangeByteStream) loops to fill a full length-prefixed frame.
type Stream struct {
	s network.Stream
}

func (st *Stream) Write(p []byte) (int, error) {
	return st.s.Write(p)
}

// Read blocks like io.Reader.Read, returning up to maxLen bytes. On stream
// close/EOF this returns a non-nil error whose Error() string is exactly
// "EOF" (gomobile can't propagate the io.EOF sentinel itself across the
// binding boundary) — Kotlin compares the message text for that case.
func (st *Stream) Read(maxLen int) ([]byte, error) {
	buf := make([]byte, maxLen)
	n, err := st.s.Read(buf)
	if err != nil {
		return nil, err
	}
	return buf[:n], nil
}

func (st *Stream) Close() error {
	return st.s.Close()
}

func (st *Stream) RemotePeerId() string {
	return st.s.Conn().RemotePeer().String()
}
