package fileserver

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/labstack/echo/v5"
	"github.com/lithammer/shortuuid/v4"

	storepb "github.com/usememos/memos/proto/gen/store"
	"github.com/usememos/memos/store"
)

const (
	// maxUploadBytes is a defensive ceiling (1 TiB) for a single upload.
	// The pipeline is fully streaming, so this is not a memory bound.
	maxUploadBytes int64 = 1 << 40

	// uploadTempFolder holds in-flight upload files until they are moved
	// to their final location under assets/.
	uploadTempFolder = ".upload_tmp"

	// assetsFolder is the root for locally stored attachment files.
	assetsFolder = "assets"
)

// registerUploadRoutes registers the streaming attachment upload endpoint.
// It lives outside /file/* so it is not gated by the gateway's 256 MiB
// MaxBytesHandler; uploads stream to disk instead of loading into memory.
func (s *FileServerService) registerUploadRoutes(echoServer *echo.Echo) {
	echoServer.POST("/upload/attachments", s.uploadAttachment)
}

// uploadAttachment streams a multipart file part to disk and records it as a
// LOCAL-storage attachment. It deliberately bypasses CreateAttachment, whose
// EXIF stripping and motion detection are whole-blob in-memory operations.
func (s *FileServerService) uploadAttachment(c *echo.Context) error {
	ctx := c.Request().Context()
	user, err := s.getCurrentUser(ctx, c)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to get current user").Wrap(err)
	}
	if user == nil {
		return echo.NewHTTPError(http.StatusUnauthorized, "unauthorized access")
	}

	multipartReader, err := c.Request().MultipartReader()
	if err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, "invalid multipart request").Wrap(err)
	}

	tmpDir := filepath.Join(s.Profile.Data, uploadTempFolder)
	if err := os.MkdirAll(tmpDir, 0o770); err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to create upload directory").Wrap(err)
	}

	attachmentUID := shortuuid.New()
	tmpPath := filepath.Join(tmpDir, attachmentUID)
	tmpFile, err := os.Create(tmpPath)
	if err != nil {
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to create upload file").Wrap(err)
	}

	filename := ""
	mimeType := "application/octet-stream"
	var size int64
	var readErr error

	cleanup := func() {
		tmpFile.Close()
		_ = os.Remove(tmpPath)
	}

	for {
		part, partErr := multipartReader.NextPart()
		if partErr == io.EOF {
			break
		}
		if partErr != nil {
			readErr = partErr
			break
		}

		if part.FormName() == "file" {
			if name := part.FileName(); name != "" {
				filename = name
			}
			if contentType := part.Header.Get("Content-Type"); contentType != "" {
				mimeType = contentType
			}
			size, err = io.Copy(tmpFile, io.LimitReader(part, maxUploadBytes+1))
			part.Close()
			if err != nil {
				readErr = err
				break
			}
			if size > maxUploadBytes {
				readErr = fmt.Errorf("upload exceeds the %d byte limit", maxUploadBytes)
				break
			}
		} else {
			// Drain and discard any other form fields.
			_, _ = io.Copy(io.Discard, part)
			part.Close()
		}
	}

	if readErr != nil {
		cleanup()
		slog.Error("failed to read upload", "error", readErr)
		return echo.NewHTTPError(http.StatusBadRequest, "failed to read upload").Wrap(readErr)
	}
	if filename == "" || size == 0 {
		cleanup()
		return echo.NewHTTPError(http.StatusBadRequest, "missing file part")
	}
	if !validateUploadFilename(filename) {
		cleanup()
		return echo.NewHTTPError(http.StatusBadRequest, "invalid filename")
	}

	normalizedMime, ok := normalizeUploadMimeType(mimeType)
	if !ok {
		cleanup()
		return echo.NewHTTPError(http.StatusBadRequest, "invalid MIME type format")
	}

	// Final path mirrors the default LOCAL template: assets/{ts}_{uid}_{filename}.
	osPath := filepath.Join(s.Profile.Data, assetsFolder, fmt.Sprintf("%d_%s_%s", time.Now().Unix(), attachmentUID, filename))
	if err := os.MkdirAll(filepath.Dir(osPath), 0o770); err != nil {
		cleanup()
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to create asset directory").Wrap(err)
	}
	if err := tmpFile.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to finalize upload").Wrap(err)
	}
	if err := os.Rename(tmpPath, osPath); err != nil {
		_ = os.Remove(tmpPath)
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to move upload into place").Wrap(err)
	}

	reference, err := filepath.Rel(s.Profile.Data, osPath)
	if err != nil {
		_ = os.Remove(osPath)
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to resolve upload path").Wrap(err)
	}

	create := &store.Attachment{
		UID:         attachmentUID,
		CreatorID:   user.ID,
		Filename:    filename,
		Type:        normalizedMime,
		Size:        size,
		StorageType: storepb.AttachmentStorageType_LOCAL,
		Reference:   filepath.ToSlash(reference),
	}
	attachment, err := s.Store.CreateAttachment(ctx, create)
	if err != nil {
		_ = os.Remove(osPath)
		slog.Error("failed to create attachment record", "error", err)
		return echo.NewHTTPError(http.StatusInternalServerError, "failed to create attachment").Wrap(err)
	}

	return c.JSON(http.StatusOK, marshalUploadedAttachment(attachment))
}

// marshalUploadedAttachment renders the created attachment in the same shape
// the web client expects from CreateAttachment (protobuf JSON, camelCase).
func marshalUploadedAttachment(attachment *store.Attachment) map[string]any {
	return map[string]any{
		"name":       fmt.Sprintf("%s%s", "attachments/", attachment.UID),
		"filename":   attachment.Filename,
		"type":       attachment.Type,
		"size":       fmt.Sprintf("%d", attachment.Size),
		"createTime": time.Unix(attachment.CreatedTs, 0).UTC().Format(time.RFC3339Nano),
	}
}

// validateUploadFilename rejects path traversal and control characters.
func validateUploadFilename(filename string) bool {
	if filename == "" || len(filename) > 255 {
		return false
	}
	if !filepath.IsLocal(filename) || strings.ContainsAny(filename, "/\\") {
		return false
	}
	if strings.HasPrefix(filename, " ") || strings.HasSuffix(filename, " ") ||
		strings.HasPrefix(filename, ".") || strings.HasSuffix(filename, ".") {
		return false
	}
	for _, r := range filename {
		if r < 0x20 || r == 0x7f {
			return false
		}
	}
	return true
}

// normalizeUploadMimeType validates and normalizes a client-provided MIME type.
func normalizeUploadMimeType(mimeType string) (string, bool) {
	mimeType = strings.TrimSpace(mimeType)
	if mimeType == "" || len(mimeType) > 255 {
		return "", false
	}
	mediaType, _, err := mime.ParseMediaType(mimeType)
	if err != nil || mediaType == "" || len(mediaType) > 255 {
		return "", false
	}
	return mediaType, true
}

// cleanupOrphanAssets removes files under assets/ that no longer have an
// attachment record (interrupted uploads, deleted records), plus any leftover
// upload temp files. Runs once at startup.
func (s *FileServerService) cleanupOrphanAssets(ctx context.Context) {
	assetsDir := filepath.Join(s.Profile.Data, assetsFolder)
	entries, err := os.ReadDir(assetsDir)
	if err != nil {
		if !os.IsNotExist(err) {
			slog.Warn("failed to scan assets dir", "error", err)
		}
		return
	}
	if len(entries) == 0 {
		return
	}

	attachments, err := s.Store.ListAttachments(ctx, &store.FindAttachment{})
	if err != nil {
		slog.Warn("failed to list attachments for orphan cleanup", "error", err)
		return
	}

	known := make(map[string]bool, len(attachments))
	for _, attachment := range attachments {
		if attachment.StorageType == storepb.AttachmentStorageType_LOCAL && attachment.Reference != "" {
			known[filepath.Clean(attachment.Reference)] = true
		}
	}

	removed := 0
	_ = filepath.WalkDir(assetsDir, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return nil
		}
		if entry.IsDir() {
			return nil
		}
		rel, relErr := filepath.Rel(s.Profile.Data, path)
		if relErr != nil {
			return nil
		}
		if !known[filepath.Clean(rel)] {
			if rmErr := os.Remove(path); rmErr == nil {
				removed++
			}
		}
		return nil
	})

	// Clear stale upload temp files.
	tmpDir := filepath.Join(s.Profile.Data, uploadTempFolder)
	if tmpEntries, tmpErr := os.ReadDir(tmpDir); tmpErr == nil {
		for _, entry := range tmpEntries {
			_ = os.Remove(filepath.Join(tmpDir, entry.Name()))
		}
	}

	if removed > 0 {
		slog.Info("cleaned orphan attachment files", "count", removed)
	}
}
