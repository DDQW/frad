package node

// PeerFoundListener receives async DHT discovery results. Implemented in Kotlin.
type PeerFoundListener interface {
	OnPeerFound(peerId string)
	OnDiscoveryError(message string)
}

// IncomingStreamListener receives async inbound libp2p streams. Implemented in
// Kotlin; Host.AcceptStream must be called with the given streamHandle to
// actually take ownership of the stream (each handle is consumed exactly
// once).
type IncomingStreamListener interface {
	OnIncomingStream(protocolId string, peerId string, streamHandle string)
}
