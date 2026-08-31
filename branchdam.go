// Package branchdam is the gomobile-bound public API for the branchDAM mobile
// companion. The gomobile tool (golang.org/x/mobile/cmd/gomobile bind) reads
// this package and produces:
//
//	android/app/libs/branchdam.aar      (Kotlin/Java consumer: io.branchdam.core.Engine)
//	ios/Frameworks/branchdam.xcframework (Swift consumer: import branchdam)
//
// The Swift xcframework is named branchdam (lowercase) so its Swift module
// name matches the Obj-C class prefix that gomobile emits for the Go package
// "branchdam"; that match is what lets Swift's Obj-C import see the types
// as `branchdam.Engine`, `branchdam.EngineOptions`, etc.
//
// The actual implementation lives in github.com/s3ntin3l8/branchdam-mobile/core
// (subpackages client, engine, hasher, queue). Sub-issue A ships a minimal
// stub: enough surface for the build pipeline to produce valid artifacts and
// for shell smoke tests to confirm the artifacts load. Sub-issue B fleshes out
// the full Engine API (sync, lineage, reclaim, etc.).
package branchdam

// Version is the package's reported build version. Smoke-test only in A;
// the canonical version lives in release-please manifests.
func Version() string {
	return "0.5.0-a-stub"
}

// EngineOptions configures a new Engine. In A this is a stub; in B the fields
// are consumed (DB path, base URL, API key, etc.).
type EngineOptions struct {
	DBPath         string
	BaseURL        string
	APIKey         string
	AgentID        string
	ClientVersion  string
	HTTPTimeoutSec int
}

// Engine is the long-lived handle to a branchdam session. In A it carries no
// real state; in B it owns the SQLite queue, the HTTP client, and the engine.
type Engine struct {
	opts EngineOptions
}

// NewEngine returns a new Engine. In A it is a stub that does no I/O and
// always succeeds. In B it opens the SQLite queue, constructs the HTTP
// client, and returns a real engine.
func NewEngine(opts EngineOptions) (*Engine, error) {
	if opts.ClientVersion == "" {
		opts.ClientVersion = Version()
	}
	return &Engine{opts: opts}, nil
}

// Close releases resources held by the engine. Idempotent: calling Close on
// an already-closed engine is a no-op.
func (e *Engine) Close() error {
	return nil
}

// Options returns the options the engine was constructed with. Smoke-test
// helper for shells verifying artifact loading.
func (e *Engine) Options() EngineOptions {
	return e.opts
}
