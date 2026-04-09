import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const ADB = process.env.ADB || "adb";

interface AdbResult {
  stdout: string;
  stderr: string;
}

async function adb(...args: string[]): Promise<AdbResult> {
  const { stdout, stderr } = await execFileAsync(ADB, args, {
    maxBuffer: 10 * 1024 * 1024,
  });
  return { stdout, stderr };
}

/** Run a shell command on the device */
export async function shell(cmd: string): Promise<string> {
  const { stdout } = await adb("shell", cmd);
  return stdout.trim();
}

/** Push a file to the device */
export async function push(localPath: string, remotePath: string): Promise<void> {
  await adb("push", localPath, remotePath);
}

/** Install an APK via adb install */
export async function install(apkPath: string): Promise<void> {
  await adb("install", apkPath);
}

/** Uninstall a package */
export async function uninstall(packageName: string): Promise<void> {
  await adb("uninstall", packageName);
}

/** Wait for the device to be available */
export async function waitForDevice(): Promise<void> {
  await adb("wait-for-device");
}

/** List installed packages */
export async function listPackages(): Promise<string[]> {
  const output = await shell("pm list packages");
  return output
    .split("\n")
    .map((line) => line.replace("package:", "").trim())
    .filter((pkg) => pkg.length > 0);
}

/** Check if a specific package is installed */
export async function isInstalled(packageName: string): Promise<boolean> {
  const packages = await listPackages();
  return packages.includes(packageName);
}

/** Send a broadcast with string extras.
 *  When `component` is provided (e.g. "com.example/.MyReceiver") the broadcast
 *  is sent as an explicit intent via `-n`, which bypasses the stopped-state
 *  restriction on freshly-installed apps that have never been launched.
 */
export async function broadcast(
  action: string,
  extras?: Record<string, string>,
  component?: string
): Promise<string> {
  let cmd = `am broadcast -a ${action}`;
  if (component) {
    cmd += ` -n ${component}`;
  }
  if (extras) {
    for (const [key, value] of Object.entries(extras)) {
      cmd += ` --es ${key} ${value}`;
    }
  }
  return shell(cmd);
}

/**
 * Wait for the system to be fully ready (PackageManagerService available).
 *
 * `adb wait-for-device` only confirms adbd is up, which happens well before
 * system_server finishes initializing. This polls `service check package` to
 * confirm PMS is available, then waits an extra settle period to let PMS
 * finish restoring sessions from install_sessions.xml.
 */
export async function waitForSystemReady(
  timeoutMs: number,
  pollMs: number,
  settleMs: number
): Promise<void> {
  await waitForDevice();

  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const output = await shell("service check package");
      if (output.includes("found")) {
        // PMS is registered — wait a bit longer for it to finish restoring
        // installer sessions from install_sessions.xml
        await new Promise((resolve) => setTimeout(resolve, settleMs));
        return;
      }
    } catch {
      // Shell call may fail while system_server is still starting
    }
    await new Promise((resolve) => setTimeout(resolve, pollMs));
  }

  throw new Error(
    `Timed out after ${timeoutMs}ms waiting for PackageManagerService. ` +
    `The device may be stuck. Check: adb shell service check package`
  );
}

/**
 * Poll until a package appears in the package list.
 * @returns true if found, false if timed out
 */
export async function pollForPackage(
  packageName: string,
  intervalMs: number,
  timeoutMs: number
): Promise<boolean> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      await waitForDevice();
      if (await isInstalled(packageName)) {
        return true;
      }
    } catch {
      // Device may be rebooting, keep trying
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  return false;
}
