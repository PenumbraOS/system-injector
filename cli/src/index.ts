#!/usr/bin/env node

import { bootstrap, installApk, status } from "./install.js";

const args = process.argv.slice(2);
const command = args[0];

async function main(): Promise<void> {
  switch (command) {
    case "install": {
      const apkPath = args[1];
      if (!apkPath) {
        console.error("Usage: system-injector install <apk-path>");
        process.exit(1);
      }
      await installApk(apkPath);
      break;
    }

    case "bootstrap": {
      const installerApk = args[1]; // optional
      const exploitApk = args[2]; // optional
      await bootstrap(installerApk, exploitApk);
      break;
    }

    case "status": {
      await status();
      break;
    }

    default: {
      console.log("system-injector — Install APKs as system UID (1000) on the Humane AI Pin\n");
      console.log("Commands:");
      console.log("  install <apk-path>                    Install an APK as system UID");
      console.log("  bootstrap [installer.apk] [exploit.apk]  Bootstrap the on-device installer");
      console.log("  status                                Check installation status");
      process.exit(command ? 1 : 0);
    }
  }
}

main().catch((err) => {
  console.error(`\nError: ${err.message}`);
  process.exit(1);
});
