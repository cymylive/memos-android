//go:build tools

// Package tools pins build tool dependencies (gomobile) in go.mod so that
// `gomobile bind` works under Go 1.24+ module-aware tooling (see
// https://go.dev/issue/77183).
package tools

import (
	_ "golang.org/x/mobile/cmd/gomobile"
)