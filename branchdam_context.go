package branchdam

import (
	"context"
	"time"
)

// Context is the gomobile context type. The shell wraps a platform-native
// context (DispatchQueue.main on iOS, CoroutineContext on Android) and
// passes it to the Engine. The wrapper propagates the shell's lifecycle
// (BGTask expiration, Activity stop) to Go's stdlib context.
//
// gomobile maps `Context` to a Swift class / Kotlin interface; the shell
// calls `Engine.methodName(ctx, ...)` and never sees this Go type
// directly. Implementations of Context on the gomobile side are typically
// produced by the gomobile-generated bindings.
type Context interface {
	// Done returns a channel that's closed when the context is cancelled.
	Done() <-chan struct{}
	// Err returns the cancellation reason, or nil if not yet cancelled.
	Err() error
	// Deadline returns the absolute deadline, or (zero time, false) if none.
	Deadline() (time.Time, bool)
}

// Compile-time check: the Go stdlib's context.Context implements Context.
var _ Context = (context.Context)(nil)

// gomobileContextToStd converts a gomobile Context (or a stdlib
// context.Context, since they share the same interface) to the stdlib
// context. The stdlib type is what core/engine/client accept.
//
// A nil input becomes context.Background().
func gomobileContextToStd(c Context) context.Context {
	if c == nil {
		return context.Background()
	}
	if std, ok := c.(context.Context); ok {
		return std
	}
	// Wrap an arbitrary Context implementation in a stdlib context.
	deadline, hasDeadline := c.Deadline()
	if hasDeadline {
		return withDeadlineFrom(c, deadline)
	}
	return withCancelFrom(c)
}

// withDeadlineFrom returns a stdlib context.Context that is cancelled
// when src is done OR at deadline, whichever comes first.
func withDeadlineFrom(src Context, deadline time.Time) context.Context {
	std, cancel := context.WithDeadline(context.Background(), deadline)
	go func() {
		select {
		case <-src.Done():
			cancel()
		case <-std.Done():
		}
	}()
	return std
}

// withCancelFrom returns a stdlib context.Context that is cancelled
// when src is done.
func withCancelFrom(src Context) context.Context {
	std, cancel := context.WithCancel(context.Background())
	go func() {
		select {
		case <-src.Done():
			cancel()
		case <-std.Done():
		}
	}()
	return std
}
