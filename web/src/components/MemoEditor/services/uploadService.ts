import { create } from "@bufbuild/protobuf";
import { timestampFromDate } from "@bufbuild/protobuf/wkt";
import { attachmentServiceClient } from "@/connect";
import type { Attachment } from "@/types/proto/api/v1/attachment_service_pb";
import { AttachmentSchema, MotionMediaSchema } from "@/types/proto/api/v1/attachment_service_pb";
import type { LocalFile } from "../types/attachment";

// Files at or above this size are uploaded via the streaming multipart
// endpoint instead of the base64 JSON path, which loads the whole file
// into memory and is capped at ~192 MB by the server request limit.
const STREAMING_UPLOAD_THRESHOLD = 32 * 1024 * 1024;

type UploadProgress = (progress: number) => void;

async function uploadViaJson(localFile: LocalFile): Promise<Attachment> {
  const { file, motionMedia } = localFile;
  const buffer = new Uint8Array(await file.arrayBuffer());
  return attachmentServiceClient.createAttachment({
    attachment: create(AttachmentSchema, {
      filename: file.name,
      size: BigInt(file.size),
      type: file.type,
      content: buffer,
      motionMedia: motionMedia ? create(MotionMediaSchema, motionMedia) : undefined,
    }),
  });
}

function uploadViaStreaming(file: File, onProgress: UploadProgress): Promise<Attachment> {
  return new Promise<Attachment>((resolve, reject) => {
    const formData = new FormData();
    formData.append("file", file, file.name);

    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${window.location.origin}/upload/attachments`);
    xhr.responseType = "json";
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300 && xhr.response) {
        resolve(toAttachment(xhr.response));
      } else {
        const message = xhr.response?.message ?? `上传失败（HTTP ${xhr.status}）`;
        reject(new Error(message));
      }
    };
    xhr.onerror = () => reject(new Error("上传失败：网络错误"));
    xhr.send(formData);
  });
}

function toAttachment(json: Record<string, unknown>): Attachment {
  const size = typeof json.size === "string" ? BigInt(json.size) : BigInt(Number(json.size ?? 0));
  return create(AttachmentSchema, {
    name: String(json.name ?? ""),
    filename: String(json.filename ?? ""),
    type: String(json.type ?? ""),
    size,
    createTime: json.createTime ? timestampFromDate(new Date(String(json.createTime))) : undefined,
  });
}

export const uploadService = {
  async uploadFiles(localFiles: LocalFile[], onProgress: UploadProgress = () => {}): Promise<Attachment[]> {
    if (localFiles.length === 0) return [];

    const attachments: Attachment[] = [];
    for (const localFile of localFiles) {
      const { file } = localFile;
      if (file.size >= STREAMING_UPLOAD_THRESHOLD) {
        attachments.push(await uploadViaStreaming(file, onProgress));
      } else {
        attachments.push(await uploadViaJson(localFile));
      }
    }
    return attachments;
  },
};
