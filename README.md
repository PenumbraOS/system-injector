# System App Injector

An injector of arbitrary apps into an Android device, marking them as "system" or "platform" apps. This exploits CVE-2024-34740 and is based heavily on [https://github.com/michalbednarski/AbxOverflow](AbxOverflow). Once installed, the injector runs as a part of the `system_server` process and awaits external commands to install APKs.

An example installation process is demonstrated in Node in `/cli`.

## Installation

`npx system-injector bootstrap` will:

1. Install the "normal" `com.penumbraos.systeminjector.exploit` app to the device
2. Copy the final `com.penumbraos.systeminjector` APK to the device
3. Trigger STAGE 1 of the exploit
4. Soft reboot the device (crash `system_server`)
5. Trigger STAGE 2 of the exploit, installing `com.penumbraos.systeminjector`
6. Soft reboot the device

At this point the System Injector installer is ready to be used.

## Usage

`npx system-injector install [APK PATH]` will:

1. Send the APK to the `systeminjector` process
2. Resign the APK with the required, shared signing identity used here (and in [https://github.com/michalbednarski/AbxOverflow](AbxOverflow))
3. Install the APK using the exploit path
4. Soft reboot the device

## Limitations

- Apps cannot normally launch when installed in this way as AMS will attempt to launch them with `seinfo=_app`. However, since we can patch `system_server`, we can override the `seinfo`
- System app directories are not available due to SELinux problems as well
