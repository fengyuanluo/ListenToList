import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";

type ServerHandle = {
  port: number;
  baseUrl: string;
  stop(): void;
};

const OPENLIST_TOKEN = "openlist-smoke-token";
const OPENLIST_FILES = ["/music/test-openlist.wav", "/music/test-openlist-next.wav"] as const;
const OPENLIST_UNSTABLE_FILES = ["/unstable/test-openlist-timeout-next.wav", "/unstable/test-openlist-timeout.wav"] as const;
const WEBDAV_FILES = ["/music/test-webdav.wav", "/music/test-webdav-next.wav"] as const;

function json(data: unknown, init: ResponseInit = {}): Response {
  const payload = JSON.stringify(data);
  return new Response(payload, {
    ...init,
    headers: {
      "content-type": "application/json",
      "content-length": String(Buffer.byteLength(payload)),
      ...(init.headers ?? {}),
    },
  });
}

function xml(text: string, init: ResponseInit = {}): Response {
  return new Response(text, {
    ...init,
    headers: {
      "content-type": "application/xml; charset=utf-8",
      "content-length": String(Buffer.byteLength(text)),
      ...(init.headers ?? {}),
    },
  });
}

function readJsonBody(req: Request): Promise<any> {
  return req.text().then((text) => {
    if (!text) return {};
    return JSON.parse(text);
  });
}

function createWaveBuffer({
  seconds = 1.2,
  sampleRate = 16000,
  frequency = 440,
}: {
  seconds?: number;
  sampleRate?: number;
  frequency?: number;
} = {}): Buffer {
  const samples = Math.floor(seconds * sampleRate);
  const bytesPerSample = 2;
  const blockAlign = bytesPerSample;
  const dataSize = samples * bytesPerSample;
  const buffer = Buffer.alloc(44 + dataSize);

  buffer.write("RIFF", 0);
  buffer.writeUInt32LE(36 + dataSize, 4);
  buffer.write("WAVE", 8);
  buffer.write("fmt ", 12);
  buffer.writeUInt32LE(16, 16);
  buffer.writeUInt16LE(1, 20);
  buffer.writeUInt16LE(1, 22);
  buffer.writeUInt32LE(sampleRate, 24);
  buffer.writeUInt32LE(sampleRate * blockAlign, 28);
  buffer.writeUInt16LE(blockAlign, 32);
  buffer.writeUInt16LE(16, 34);
  buffer.write("data", 36);
  buffer.writeUInt32LE(dataSize, 40);

  for (let i = 0; i < samples; i += 1) {
    const t = i / sampleRate;
    const sample = Math.sin(2 * Math.PI * frequency * t);
    buffer.writeInt16LE(Math.round(sample * 0.35 * 32767), 44 + i * 2);
  }
  return buffer;
}

function rangeResponse(req: Request, bytes: Buffer, contentType = "audio/wav"): Response {
  const range = req.headers.get("range");
  if (!range) {
    return new Response(bytes, {
      status: 200,
      headers: {
        "content-type": contentType,
        "content-length": String(bytes.byteLength),
        "accept-ranges": "bytes",
        connection: "close",
      },
    });
  }

  const match = /^bytes=(\d+)-$/i.exec(range.trim());
  const start = match ? Number.parseInt(match[1], 10) : 0;
  const slice = bytes.subarray(start);
  return new Response(slice, {
    status: 206,
    headers: {
      "content-type": contentType,
      "content-length": String(slice.byteLength),
      "content-range": `bytes ${start}-${bytes.byteLength - 1}/${bytes.byteLength}`,
      "accept-ranges": "bytes",
      connection: "close",
    },
  });
}

function stalledReadResponse(contentType = "audio/wav"): Response {
  return new Response(
    new ReadableStream({
      start() {
        // Keep the response open after headers so the client hits its read timeout.
      },
    }),
    {
      status: 200,
      headers: {
        "content-type": contentType,
        "accept-ranges": "bytes",
        connection: "close",
      },
    },
  );
}

function webdavListing(basePath: string, files: Array<{ filePath: string; bytes: Buffer }>): string {
  const items = files
    .map(({ filePath, bytes }) => {
      const escapedName = path.posix.basename(filePath);
      const fullPath = `${basePath}${filePath}`;
      return `
  <response>
    <href>${fullPath}</href>
    <propstat>
      <prop>
        <displayname>${escapedName}</displayname>
        <resourcetype />
        <getcontentlength>${bytes.byteLength}</getcontentlength>
      </prop>
    </propstat>
  </response>`;
    })
    .join("");

  return `<?xml version="1.0" encoding="utf-8"?>
<multistatus xmlns="DAV:">
  <response>
    <href>${basePath}/music/</href>
    <propstat>
      <prop>
        <displayname>music</displayname>
        <resourcetype><collection /></resourcetype>
      </prop>
    </propstat>
  </response>${items}
</multistatus>`;
}

export function writeSmokeWaveArtifact(filePath: string, frequency = 440, seconds = 20): string {
  mkdirSync(path.dirname(filePath), { recursive: true });
  writeFileSync(filePath, createWaveBuffer({ frequency, seconds }));
  return filePath;
}

export function startMockPlaybackServer(port = 18080): ServerHandle {
  const openListBuffers: Record<string, Buffer> = {
    [OPENLIST_FILES[0]]: createWaveBuffer({ frequency: 440, seconds: 20 }),
    [OPENLIST_FILES[1]]: createWaveBuffer({ frequency: 554, seconds: 20 }),
    [OPENLIST_UNSTABLE_FILES[0]]: createWaveBuffer({ frequency: 330, seconds: 20 }),
    [OPENLIST_UNSTABLE_FILES[1]]: createWaveBuffer({ frequency: 660, seconds: 20 }),
  };
  const stalledOpenListPaths = new Set<string>([OPENLIST_UNSTABLE_FILES[0]]);
  const webDavBuffers: Record<string, Buffer> = {
    [WEBDAV_FILES[0]]: createWaveBuffer({ frequency: 660, seconds: 20 }),
    [WEBDAV_FILES[1]]: createWaveBuffer({ frequency: 880, seconds: 20 }),
  };

  const server = Bun.serve({
    port,
    fetch: async (req) => {
      const url = new URL(req.url);
      console.log(`[mock-playback] ${req.method} ${url.pathname}`);

      if (url.pathname === "/healthz") {
        return new Response("ok");
      }

      if (req.method === "POST" && url.pathname === "/openlist/api/auth/login") {
        return json({
          code: 200,
          message: "success",
          data: { token: OPENLIST_TOKEN },
        });
      }

      if (req.method === "POST" && url.pathname === "/openlist/api/fs/list") {
        if (req.headers.get("authorization") !== OPENLIST_TOKEN) {
          return json(
            { code: 401, message: "unauthorized", data: null },
            { status: 401 },
          );
        }
        const body = await readJsonBody(req);
        const dir = body?.path ?? "/";
        const files = dir === "/music"
          ? [...OPENLIST_FILES]
          : dir === "/unstable"
            ? [...OPENLIST_UNSTABLE_FILES]
            : null;
        const items = files
          ? files.map((filePath) => ({
              name: path.posix.basename(filePath),
              size: openListBuffers[filePath]!.byteLength,
              is_dir: false,
              sign: "smoke-sign",
              type: 4,
            }))
          : dir === "/"
            ? [
                { name: "music", size: null, is_dir: true, sign: "", type: 1 },
                { name: "unstable", size: null, is_dir: true, sign: "", type: 1 },
              ]
            : [];
        return json({
          code: 200,
          message: "success",
          data: { content: items, total: items.length },
        });
      }

      if (req.method === "POST" && url.pathname === "/openlist/api/fs/get") {
        const body = await readJsonBody(req);
        const requestedPath = String(body?.path ?? OPENLIST_FILES[0]);
        const fileBytes = openListBuffers[requestedPath as keyof typeof openListBuffers];
        if (!fileBytes) {
          return json({ code: 404, message: "not found", data: null }, { status: 404 });
        }
        return json({
          code: 200,
          message: "success",
          data: {
            name: path.posix.basename(requestedPath),
            size: fileBytes.byteLength,
            is_dir: false,
            sign: "smoke-sign",
            type: 4,
            raw_url: `http://127.0.0.1:${port}/media/openlist${requestedPath}`,
          },
        });
      }

      if (req.method === "GET" && url.pathname.startsWith("/media/openlist/")) {
        const requestedPath = url.pathname.replace("/media/openlist", "");
        const fileBytes = openListBuffers[requestedPath as keyof typeof openListBuffers];
        if (!fileBytes) {
          return new Response("not found", { status: 404 });
        }
        if (stalledOpenListPaths.has(requestedPath)) {
          return stalledReadResponse();
        }
        return rangeResponse(req, fileBytes);
      }

      if (req.method === "PROPFIND" && url.pathname === "/webdav/music/") {
        return xml(
          webdavListing(
            "/webdav",
            WEBDAV_FILES.map((filePath) => ({ filePath, bytes: webDavBuffers[filePath]! })),
          ),
          {
            status: 207,
            headers: {
              connection: "close",
            },
          },
        );
      }

      if ((req.method === "GET" || req.method === "HEAD") && url.pathname.startsWith("/webdav/")) {
        const requestedPath = url.pathname.replace("/webdav", "");
        const fileBytes = webDavBuffers[requestedPath as keyof typeof webDavBuffers];
        if (!fileBytes) {
          if (req.method === "HEAD") {
            return new Response(null, {
              status: 404,
              headers: {
                "content-length": "0",
                connection: "close",
              },
            });
          }
          return new Response("not found", {
            status: 404,
            headers: {
              "content-type": "text/plain; charset=utf-8",
              "content-length": String(Buffer.byteLength("not found")),
              connection: "close",
            },
          });
        }
        if (req.method === "HEAD") {
          return new Response(null, {
            status: 200,
            headers: {
              "content-type": "audio/wav",
              "content-length": String(fileBytes.byteLength),
              "accept-ranges": "bytes",
              connection: "close",
            },
          });
        }
        return rangeResponse(req, fileBytes);
      }

      return new Response("not found", { status: 404 });
    },
  });

  return {
    port: server.port,
    baseUrl: `http://127.0.0.1:${server.port}`,
    stop() {
      server.stop(true);
    },
  };
}

if (import.meta.main) {
  const portArg = process.argv.find((arg) => arg.startsWith("--port="));
  const port = portArg ? Number.parseInt(portArg.split("=")[1]!, 10) : 18080;
  const server = startMockPlaybackServer(port);
  console.log(`mock playback server listening on ${server.baseUrl}`);
}
