// Package mobile exposes the memos server as a library callable from
// Android (via gomobile bind). The server binds to all interfaces so
// devices on the same LAN can reach it, stores all data (SQLite DB,
// attachments, thumbnails, motion caches) inside the given dataDir.
package mobile

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"sync"
	"time"

	"github.com/usememos/memos/internal/profile"
	"github.com/usememos/memos/internal/version"
	"github.com/usememos/memos/server"
	"github.com/usememos/memos/store"
	"github.com/usememos/memos/store/db"
)

// serverReadyTimeout is how long StartServer waits for the HTTP server to
// accept connections before reporting failure.
const serverReadyTimeout = 10 * time.Second

var (
	stateMu   sync.Mutex
	srvHandle *server.Server
	cancel    context.CancelFunc
)

// StartServer starts the memos server bound to 0.0.0.0:port (LAN-accessible)
// with its data directory rooted at dataDir. The data directory must already
// exist and be writable. It returns an empty string on success, otherwise a
// human-readable error message.
func StartServer(dataDir string, port int) string {
	stateMu.Lock()
	defer stateMu.Unlock()

	if srvHandle != nil {
		return "server is already running"
	}

	instanceProfile := &profile.Profile{
		Demo:    false,
		Addr:    "0.0.0.0",
		Port:    port,
		Data:    dataDir,
		Driver:  "sqlite",
		Version: version.GetCurrentVersion(),
	}
	if err := instanceProfile.Validate(); err != nil {
		return fmt.Sprintf("failed to validate profile: %v", err)
	}

	ctx, serverCancel := context.WithCancel(context.Background())

	dbDriver, err := db.NewDBDriver(instanceProfile)
	if err != nil {
		serverCancel()
		return fmt.Sprintf("failed to create db driver: %v", err)
	}

	storeInstance := store.New(dbDriver, instanceProfile)
	if err := storeInstance.Migrate(ctx); err != nil {
		serverCancel()
		return fmt.Sprintf("failed to migrate database: %v", err)
	}
	if err := storeInstance.LoadDeploymentConfiguration(ctx); err != nil {
		serverCancel()
		return fmt.Sprintf("failed to load deployment configuration: %v", err)
	}

	srv, err := server.NewServer(ctx, instanceProfile, storeInstance)
	if err != nil {
		serverCancel()
		return fmt.Sprintf("failed to create server: %v", err)
	}

	srvHandle = srv
	cancel = serverCancel

	go func() {
		if err := srv.Start(ctx); err != nil {
			slog.Error("failed to start server", "error", err)
		}
	}()

	if err := waitForReady(port, serverReadyTimeout); err != nil {
		shutdownLocked()
		return fmt.Sprintf("server failed to start: %v", err)
	}

	return ""
}

// StopServer gracefully shuts down the memos server, checkpointing SQLite
// and closing the database. Safe to call when the server is not running.
func StopServer() {
	stateMu.Lock()
	defer stateMu.Unlock()
	shutdownLocked()
}

// IsRunning reports whether the memos server is currently running.
func IsRunning() bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	return srvHandle != nil
}

// shutdownLocked stops the running server. stateMu must be held.
func shutdownLocked() {
	if srvHandle == nil {
		return
	}
	srvHandle.Shutdown(context.Background())
	srvHandle = nil
	if cancel != nil {
		cancel()
		cancel = nil
	}
}

// waitForReady polls the port until a TCP connection succeeds or the timeout
// elapses.
func waitForReady(port int, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	address := fmt.Sprintf("127.0.0.1:%d", port)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", address, 500*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			return nil
		}
		time.Sleep(200 * time.Millisecond)
	}
	return fmt.Errorf("server did not become ready within %v", timeout)
}