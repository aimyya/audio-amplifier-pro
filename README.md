# Audio Amplifier Pro (Android)

A foreground-only, consent-based ambient audio amplifier for Android. It uses the phone microphone, live playback, adjustable gain, optional Haar-wavelet soft-threshold denoising, an adaptive noise gate, and Android's built-in NoiseSuppressor when available.

## Safety and privacy
- The app has no network permission and does not record or save audio.
- Monitoring is visible on screen and stops when the app leaves the foreground or is closed.
- Use only for sounds you are authorized to hear. It does not access another phone's microphone or listen through walls.
- Use headphones to reduce acoustic feedback. Start with low gain.

## Build APK using GitHub Actions (phone-only workflow)
1. Create a GitHub account if needed.
2. Create a new repository named `audio-amplifier-pro`.
3. Upload the contents of this ZIP to the repository root (not the ZIP file itself).
4. Open the repository's **Actions** tab.
5. Select **Build Android APK** and tap **Run workflow** (or push to `main`).
6. When the workflow completes, open the run and download the `audio-amplifier-pro-debug` artifact.
7. Extract the downloaded artifact ZIP. Install `app-debug.apk` on your Redmi.
8. If Android asks, allow installation from the browser/files app used to open the APK.

## Device setup
1. Pair Bluetooth headphones in Android Settings.
2. Install and open the APK.
3. Grant microphone permission.
4. Keep the app open, tap **START LISTENING**, and adjust amplification slowly.
5. If Bluetooth delay is distracting, try wired headphones.

## Notes
- Bluetooth output latency varies by headset and Android audio routing.
- Wavelet denoising is experimental and may distort speech; turn it off if speech sounds less natural.
- Very high gain amplifies noise and can cause feedback or uncomfortable volume. Keep headphone volume low.
