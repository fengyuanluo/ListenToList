import { appendFileSync, mkdirSync, writeFileSync } from "node:fs";
import net from "node:net";
import path from "node:path";
import { writeSmokeWaveArtifact } from "./mock-playback-server";

const PACKAGE_NAME = "com.kutedev.easemusicplayer";
const MAIN_ACTIVITY = `${PACKAGE_NAME}/com.kutedev.easemusicplayer.MainActivity`;
const DEBUG_RECEIVER = `${PACKAGE_NAME}/com.kutedev.easemusicplayer.debug.DebugSmokeReceiver`;
const DEBUG_SMOKE_ACTION = "com.kutedev.easemusicplayer.debug.SMOKE";
const DEBUG_SMOKE_TOKEN = "listen-to-list-debug-smoke-v1";
const DEFAULT_DEVICE = "172.26.121.48:34327";
const DEFAULT_PORT = 18080;

type Scenario = {
  name: string;
  expectedRoute?: "LOCAL_FILE" | "DIRECT_HTTP" | "STREAM_FALLBACK" | "DOWNLOADED_FILE" | "DOWNLOADED_CONTENT";
  payload: Record<string, unknown>;
};

type DebugSmokeResult = {
  requestId: string;
  status: string;
  stage: string;
  message: string;
  storageId?: number | null;
  playlistId?: number | null;
  musicId?: number | null;
  durationMs?: number | null;
  expectedResolverMode?: string | null;
  actualResolverMode?: string | null;
  resolvedUri?: string | null;
  routeHistory?: Array<{
    musicId?: number | null;
    route?: string | null;
    resolvedUri?: string | null;
    sourceTag?: string | null;
    resolverMode?: string | null;
    routeRefreshCount?: number | null;
    recoverySkipCount?: number | null;
    lastPlaybackErrorCode?: number | null;
    lastPlaybackErrorName?: string | null;
  }>;
  currentMetadataDurationSynced?: boolean | null;
  nextMetadataDurationSynced?: boolean | null;
};

type ServerHandle = {
  port: number;
  baseUrl: string;
  stop(): void;
};

function waitForTcpPort(host: string, port: number, timeoutMs = 1_000): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host, port });
    const finish = (ok: boolean) => {
      socket.removeAllListeners();
      socket.destroy();
      resolve(ok);
    };
    socket.setTimeout(timeoutMs);
    socket.once("connect", () => finish(true));
    socket.once("timeout", () => finish(false));
    socket.once("error", () => finish(false));
  });
}

function arg(name: string, fallback?: string): string | undefined {
  const found = process.argv.find((item) => item.startsWith(`--${name}=`));
  return found ? found.slice(name.length + 3) : fallback;
}

function runOrThrow(
  cmd: string[],
  cwd = process.cwd(),
  allowFailure = false,
  timeoutMs = 60_000,
): string {
  const started = Date.now();
  const timeoutSeconds = Math.max(1, Math.ceil(timeoutMs / 1000));
  const actualCmd = timeoutMs > 0 ? ["timeout", `${timeoutSeconds}s`, ...cmd] : cmd;
  const result = Bun.spawnSync(actualCmd, {
    cwd,
    stdout: "pipe",
    stderr: "pipe",
  });
  const stdout = Buffer.from(result.stdout).toString("utf8");
  const stderr = Buffer.from(result.stderr).toString("utf8");
  const elapsedMs = Date.now() - started;
  if (result.exitCode !== 0 && !allowFailure) {
    const reason = result.exitCode === 124 ? "命令超时" : "命令失败";
    throw new Error(
      `${reason}: ${cmd.join(" ")}\n` +
        `elapsedMs=${elapsedMs} timeoutMs=${timeoutMs} exitCode=${result.exitCode}\n` +
        `stdout:\n${stdout}\nstderr:\n${stderr}`,
    );
  }
  return stdout.trim();
}

function dumpLogcat(device: string): string {
  return runOrThrow(["adb", "-s", device, "logcat", "-d"]);
}

async function launchMockPlaybackServer(port: number): Promise<ServerHandle> {
  const proc = Bun.spawn(
    ["bun", "run", "./scripts/mock-playback-server.ts", `--port=${port}`],
    {
      cwd: process.cwd(),
      stdout: "pipe",
      stderr: "pipe",
    },
  );
  const baseUrl = `http://127.0.0.1:${port}`;
  const started = Date.now();
  while (Date.now() - started < 10_000) {
    if (await waitForTcpPort("127.0.0.1", port)) {
      return {
        port,
        baseUrl,
        stop() {
          proc.kill();
        },
      };
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }

  proc.kill();
  throw new Error(`mock playback server 启动超时: ${baseUrl}/healthz`);
}

function extractBroadcastResult(output: string): DebugSmokeResult | null {
  const match = output.match(/data="([^"]+)"/);
  if (!match) {
    return null;
  }
  const decoded = Buffer.from(match[1]!, "base64").toString("utf8");
  return JSON.parse(decoded) as DebugSmokeResult;
}

function extractLogcatResult(logcat: string, requestId: string): DebugSmokeResult | null {
  for (const line of logcat.split(/\r?\n/).reverse()) {
    if (!line.includes("DEBUG_SMOKE_RESULT") || !line.includes(requestId)) {
      continue;
    }
    const jsonStart = line.indexOf("{");
    if (jsonStart < 0) {
      continue;
    }
    return JSON.parse(line.slice(jsonStart).trim()) as DebugSmokeResult;
  }
  return null;
}

async function waitForSmokeResult(
  device: string,
  requestId: string,
  initialResult: DebugSmokeResult | null,
): Promise<DebugSmokeResult> {
  if (initialResult) {
    return initialResult;
  }
  const started = Date.now();
  while (Date.now() - started < 20_000) {
    const logcat = dumpLogcat(device);
    const fromLogcat = extractLogcatResult(logcat, requestId);
    if (fromLogcat) {
      return fromLogcat;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`广播输出和 logcat 都未找到 smoke 结果: ${requestId}`);
}

function grantPlaybackPermissions(device: string): void {
  const sdk = Number.parseInt(
    runOrThrow(["adb", "-s", device, "shell", "getprop", "ro.build.version.sdk"]) || "34",
    10,
  );
  if (sdk >= 33) {
    runOrThrow(["adb", "-s", device, "shell", "pm", "grant", PACKAGE_NAME, "android.permission.READ_MEDIA_AUDIO"], process.cwd(), true);
    runOrThrow(["adb", "-s", device, "shell", "pm", "grant", PACKAGE_NAME, "android.permission.POST_NOTIFICATIONS"], process.cwd(), true);
  } else {
    runOrThrow(["adb", "-s", device, "shell", "pm", "grant", PACKAGE_NAME, "android.permission.READ_EXTERNAL_STORAGE"], process.cwd(), true);
  }
}

function assertScenarioResult(result: DebugSmokeResult, scenario: Scenario, logcatPath: string): void {
  if (result.status !== "ok") {
    throw new Error(`${scenario.name} 失败: ${result.message}`);
  }
  if (scenario.expectedRoute && result.actualResolverMode !== scenario.expectedRoute) {
    throw new Error(
      `${scenario.name} resolver 结果不符合预期: expected=${scenario.expectedRoute} actual=${result.actualResolverMode}`,
    );
  }
  if (scenario.expectedRoute && !result.resolvedUri) {
    throw new Error(
      `${scenario.name} 缺少 resolvedUri。\nresult=${JSON.stringify(result, null, 2)}\nlogcat=${logcatPath}`,
    );
  }
  const playbackRoute = result.routeHistory?.find((entry) => entry.sourceTag === "playback");
  if (scenario.expectedRoute && playbackRoute && playbackRoute.resolverMode !== scenario.expectedRoute) {
    throw new Error(
      `${scenario.name} playback routeHistory 不符合预期: expected=${scenario.expectedRoute} actual=${playbackRoute.resolverMode}` +
        `\nresult=${JSON.stringify(result, null, 2)}\nlogcat=${logcatPath}`,
    );
  }
  if (
    "assertions" in scenario.payload &&
    typeof scenario.payload.assertions === "object" &&
    scenario.payload.assertions &&
    Array.isArray((scenario.payload.assertions as any).requiredSourceTags)
  ) {
    const requiredSourceTags = (scenario.payload.assertions as any).requiredSourceTags as string[];
    const actualTags = new Set((result.routeHistory ?? []).map((entry) => entry.sourceTag).filter(Boolean));
    for (const tag of requiredSourceTags) {
      if (!actualTags.has(tag)) {
        throw new Error(
          `${scenario.name} 缺少 sourceTag=${tag}` +
            `\nresult=${JSON.stringify(result, null, 2)}\nlogcat=${logcatPath}`,
        );
      }
    }
  }
  if (
    "assertions" in scenario.payload &&
    typeof scenario.payload.assertions === "object" &&
    scenario.payload.assertions &&
    "requireCurrentMetadataDuration" in scenario.payload.assertions &&
    (scenario.payload.assertions as any).requireCurrentMetadataDuration === true &&
    result.currentMetadataDurationSynced !== true
  ) {
    throw new Error(
      `${scenario.name} 当前曲目 metadata duration 未回填` +
        `\nresult=${JSON.stringify(result, null, 2)}\nlogcat=${logcatPath}`,
    );
  }
  if (
    "assertions" in scenario.payload &&
    typeof scenario.payload.assertions === "object" &&
    scenario.payload.assertions &&
    "requireNextMetadataDuration" in scenario.payload.assertions &&
    (scenario.payload.assertions as any).requireNextMetadataDuration === true &&
    result.nextMetadataDurationSynced !== true
  ) {
    throw new Error(
      `${scenario.name} 下一首曲目 metadata duration 未回填` +
        `\nresult=${JSON.stringify(result, null, 2)}\nlogcat=${logcatPath}`,
    );
  }
}

async function runScenario(device: string, scenario: Scenario, artifactsDir: string): Promise<DebugSmokeResult> {
  runOrThrow(["adb", "-s", device, "logcat", "-c"]);
  const payloadB64 = Buffer.from(JSON.stringify(scenario.payload), "utf8").toString("base64");
  const broadcastOutput = runOrThrow([
    "adb",
    "-s",
    device,
    "shell",
    "am",
    "broadcast",
    "-a",
    DEBUG_SMOKE_ACTION,
    "-n",
    DEBUG_RECEIVER,
    "--es",
    "token",
    DEBUG_SMOKE_TOKEN,
    "--es",
    "payload_b64",
    payloadB64,
    "--es",
    "request_id",
    String(scenario.payload.requestId),
  ]);
  const result = await waitForSmokeResult(
    device,
    String(scenario.payload.requestId),
    extractBroadcastResult(broadcastOutput),
  );
  const resultText = JSON.stringify(result, null, 2);
  writeFileSync(path.join(artifactsDir, `${scenario.name}.result.json`), resultText);

  const logcat = dumpLogcat(device);
  const logcatPath = path.join(artifactsDir, `${scenario.name}.logcat.txt`);
  writeFileSync(logcatPath, logcat);

  assertScenarioResult(result, scenario, logcatPath);
  return result;
}

async function main(): Promise<void> {
  const device = arg("device", DEFAULT_DEVICE)!;
  const port = Number.parseInt(arg("port", String(DEFAULT_PORT))!, 10);
  const apkPath = path.resolve(
    arg("apk", "android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk")!,
  );
  const artifactsDir = path.resolve("artifacts/smoke", new Date().toISOString().replaceAll(":", "-"));
  mkdirSync(artifactsDir, { recursive: true });
  const runnerEventsPath = path.join(artifactsDir, "runner-events.log");
  const logStep = (message: string): void => {
    const line = `${new Date().toISOString()} ${message}`;
    console.log(message);
    appendFileSync(runnerEventsPath, `${line}\n`);
  };

  logStep(`连接设备 ${device}`);
  runOrThrow(["adb", "connect", device], process.cwd(), true, 20_000);
  logStep(`安装 APK ${apkPath}`);
  runOrThrow(["adb", "-s", device, "install", "-r", apkPath], process.cwd(), false, 120_000);
  logStep("清理应用数据");
  runOrThrow(["adb", "-s", device, "shell", "pm", "clear", PACKAGE_NAME], process.cwd(), true, 30_000);
  logStep("授权播放相关权限");
  grantPlaybackPermissions(device);
  logStep("启动 MainActivity");
  runOrThrow(["adb", "-s", device, "shell", "am", "start", "-W", "-n", MAIN_ACTIVITY], process.cwd(), true, 30_000);
  await new Promise((resolve) => setTimeout(resolve, 2000));

  logStep("启动 mock playback server");
  const server = await launchMockPlaybackServer(port);
  try {
    logStep(`配置 adb reverse tcp:${server.port}`);
    runOrThrow(["adb", "-s", device, "reverse", `tcp:${server.port}`, `tcp:${server.port}`], process.cwd(), false, 20_000);

    const localWaveA = path.join(artifactsDir, "test-local-a.wav");
    const localWaveB = path.join(artifactsDir, "test-local-b.wav");
    logStep("生成本地 smoke WAV");
    writeSmokeWaveArtifact(localWaveA, 550);
    writeSmokeWaveArtifact(localWaveB, 770);
    logStep("创建设备本地 smoke 目录");
    runOrThrow(["adb", "-s", device, "shell", "mkdir", "-p", "/sdcard/Music/ListenToListSmoke"], process.cwd(), false, 20_000);
    logStep("推送 test-local-a.wav");
    runOrThrow(["adb", "-s", device, "push", localWaveA, "/sdcard/Music/ListenToListSmoke/test-local-a.wav"], process.cwd(), false, 30_000);
    logStep("推送 test-local-b.wav");
    runOrThrow(["adb", "-s", device, "push", localWaveB, "/sdcard/Music/ListenToListSmoke/test-local-b.wav"], process.cwd(), false, 30_000);

    const scenarios: Scenario[] = [
      {
        name: "local",
        expectedRoute: "LOCAL_FILE",
        payload: {
          requestId: "smoke-local-001",
          resetBefore: true,
          storage: {
            type: "LOCAL",
            alias: "Smoke Local",
            addr: "/",
            username: "",
            password: "",
            isAnonymous: true,
            replaceExistingAlias: true,
          },
          playlist: {
            folderPath: "/Music/ListenToListSmoke",
            targetEntryPath: "/Music/ListenToListSmoke/test-local-a.wav",
            playlistName: "Smoke /Music/ListenToListSmoke",
          },
          play: {
            auto: true,
            seekToMs: 0,
            awaitReadyTimeoutMs: 15000,
          },
          assertions: {
            expectedResolverMode: "LOCAL_FILE",
            requiredSourceTags: ["next-prefetch"],
            requireCurrentMetadataDuration: true,
            requireNextMetadataDuration: true,
            metadataWaitTimeoutMs: 15_000,
          },
        },
      },
      {
        name: "openlist",
        expectedRoute: "DIRECT_HTTP",
        payload: {
          requestId: "smoke-openlist-001",
          resetBefore: true,
          storage: {
            type: "OPEN_LIST",
            alias: "Smoke OpenList",
            addr: `${server.baseUrl}/openlist`,
            username: "user",
            password: "pass",
            isAnonymous: false,
            replaceExistingAlias: true,
          },
          playlist: {
            folderPath: "/music",
            targetEntryPath: "/music/test-openlist-next.wav",
            playlistName: "Smoke /music openlist",
          },
          play: {
            auto: true,
            seekToMs: 0,
            awaitReadyTimeoutMs: 15000,
          },
          assertions: {
            expectedResolverMode: "DIRECT_HTTP",
            requiredSourceTags: ["next-prefetch"],
            requireCurrentMetadataDuration: true,
            requireNextMetadataDuration: true,
            metadataWaitTimeoutMs: 15_000,
          },
        },
      },
      {
        name: "webdav",
        expectedRoute: "DIRECT_HTTP",
        payload: {
          requestId: "smoke-webdav-001",
          resetBefore: true,
          storage: {
            type: "WEBDAV",
            alias: "Smoke WebDAV",
            addr: `${server.baseUrl}/webdav`,
            username: "",
            password: "",
            isAnonymous: true,
            replaceExistingAlias: true,
          },
          playlist: {
            folderPath: "/music",
            targetEntryPath: "/music/test-webdav-next.wav",
            playlistName: "Smoke /music webdav",
          },
          play: {
            auto: true,
            seekToMs: 0,
            awaitReadyTimeoutMs: 15000,
          },
          assertions: {
            expectedResolverMode: "DIRECT_HTTP",
            requiredSourceTags: ["next-prefetch"],
            requireCurrentMetadataDuration: true,
            requireNextMetadataDuration: true,
            metadataWaitTimeoutMs: 15_000,
          },
        },
      },
    ];

    const smokeSummaryScenarios = [...scenarios];
    for (const scenario of scenarios) {
      logStep(`运行 scenario: ${scenario.name}`);
      await runScenario(device, scenario, artifactsDir);
    }

    const downloadPrepareScenario: Scenario = {
      name: "download-offline-prepare",
      payload: {
        requestId: "smoke-download-prepare-001",
        resetBefore: true,
        storage: {
          type: "OPEN_LIST",
          alias: "Smoke Offline OpenList",
          addr: `${server.baseUrl}/openlist`,
          username: "user",
          password: "pass",
          isAnonymous: false,
          replaceExistingAlias: true,
        },
        playlist: {
          folderPath: "/music",
          targetEntryPath: "/music/test-openlist-next.wav",
          playlistName: "Smoke offline openlist",
        },
        download: {
          waitTimeoutMs: 15_000,
        },
        play: {
          auto: false,
          seekToMs: 0,
          awaitReadyTimeoutMs: 15_000,
        },
        assertions: {},
      },
    };
    smokeSummaryScenarios.push(downloadPrepareScenario);
    logStep(`运行 scenario: ${downloadPrepareScenario.name}`);
    const downloadPrepareResult = await runScenario(device, downloadPrepareScenario, artifactsDir);
    if (!downloadPrepareResult.playlistId || !downloadPrepareResult.musicId) {
      throw new Error(
        `download-offline-prepare 未返回 playlistId/musicId。\nresult=${JSON.stringify(downloadPrepareResult, null, 2)}`,
      );
    }

    logStep("停止 mock playback server，验证下载后的离线回放");
    runOrThrow(["adb", "-s", device, "reverse", "--remove", `tcp:${server.port}`], process.cwd(), true);
    server.stop();

    const downloadOfflinePlayScenario: Scenario = {
      name: "download-offline-play",
      expectedRoute: "DOWNLOADED_FILE",
      payload: {
        requestId: "smoke-download-play-001",
        resetBefore: false,
        storage: {
          type: "OPEN_LIST",
          alias: "Smoke Offline OpenList",
          addr: `${server.baseUrl}/openlist`,
          username: "user",
          password: "pass",
          isAnonymous: false,
          replaceExistingAlias: false,
        },
        playlist: {
          folderPath: "/music",
          targetEntryPath: "/music/test-openlist-next.wav",
          playlistName: "Smoke offline openlist",
        },
        existingPlayback: {
          playlistId: downloadPrepareResult.playlistId,
          musicId: downloadPrepareResult.musicId,
        },
        play: {
          auto: true,
          seekToMs: 0,
          awaitReadyTimeoutMs: 15_000,
        },
        assertions: {
          expectedResolverMode: "DOWNLOADED_FILE",
          requireCurrentMetadataDuration: true,
          metadataWaitTimeoutMs: 15_000,
        },
      },
    };
    smokeSummaryScenarios.push(downloadOfflinePlayScenario);
    logStep(`运行 scenario: ${downloadOfflinePlayScenario.name}`);
    await runScenario(device, downloadOfflinePlayScenario, artifactsDir);

    const summary = {
      device,
      apkPath,
      serverBaseUrl: server.baseUrl,
      scenarios: smokeSummaryScenarios.map((scenario) => ({
        name: scenario.name,
        expectedRoute: scenario.expectedRoute ?? null,
        assertedSourceTag: "playback",
      })),
    };
    writeFileSync(
      path.join(artifactsDir, "summary.json"),
      JSON.stringify(summary, null, 2),
    );
    logStep(`Smoke 成功，产物目录: ${artifactsDir}`);
  } finally {
    server.stop();
  }
}

await main();
