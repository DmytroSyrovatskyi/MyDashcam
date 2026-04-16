# 🏎️ MyDashcam (BVR Pro)

![Android](https://img.shields.io/badge/Android-10%20to%2014%2B-3DDC84?style=flat-square&logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=flat-square&logo=kotlin)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

A professional Background Video Recorder (BVR) for Android. Designed with a focus on background stability, smart adaptation to shooting conditions, and minimal resource consumption.

Works flawlessly alongside navigation apps (Google Maps, Waze) or while the device screen is locked.

---

## ✨ Key Features (Smart Capabilities)

* 🎥 **True Background Recording:** Full support for Android 14 (API 34+). The service runs smoothly using the proper `ForegroundServiceTypes` (camera | microphone) without crashes.
* 🧠 **Adaptive Shooting (Smart IQ):**
    * *Dynamic Exposure (EV):* Continuously analyzes sensor ISO to prevent overexposure from oncoming headlights or when exiting tunnels.
    * *Smart Bitrate:* Automatically recalculates the bitrate in low-light conditions to avoid pixelated or "blocky" video output.
* 🔥 **Thermal Throttling Protection:** Deep integration with Android `PowerManager`. If the phone overheats under the windshield, the dashcam will gracefully reduce the processing load or safely stop recording to preserve the video file.
* 🔄 **Loop Recording:** Records in 10-minute chunks. Once the storage limit is reached, older files are automatically overwritten to free up space.
* 🔒 **Overwrite Protection:** Files can be "locked" directly from the notification panel (adding a `LOCKED_` prefix), protecting them from loop deletion.
* ⚡ **Dual Launch Modes (App Shortcuts):**
    * **Main App:** A full-fledged UI for viewing the gallery, managing/locking files, and adjusting advanced settings.
    * **Quick Start:** A separate home screen icon acting as a remote control. A single tap seamlessly starts or stops recording without opening any visible windows.

---

## 🛠 Tech Stack

* **CameraX & Camera2Interop:** For direct hardware access and advanced sensor control (Lens selection: Main, Ultra-Wide, Front Cabin).
* **Coroutines / LifecycleScope:** For asynchronous tracking of loop timers and adaptive bitrate recalculation every 150 ms.
* **MediaRecorder (HEVC/H.264):** Optimized video compression with configurable resolutions up to 4K UHD and 60 FPS.
* **Scoped Storage:** Records directly to the `Movies/MyDashcam` directory. The app relies on modern storage APIs and does not require invasive permissions to read the user's entire gallery.

---

## 📱 Interface and Controls

### Interactive Notification Panel
You don't need to open the app to control it. The notification panel adapts dynamically:
* **Standby Mode:** `[START]`, `[EXIT]` buttons.
* **Recording Mode:** `[STOP]`, `[LOCK]`, `[EXIT]` buttons.
  The panel displays live telemetry: `Active Lens | Resolution | FPS | Current ISO | Target Bitrate`.

### Advanced Settings
Fine-tuning under the hood:
* Lens selection (Ultra-Wide, Main Sensor, Cabin Front)
* Video Quality (720p HD, 1080p Full HD, 4K UHD) and Framerate (30 / 60 FPS)
* Storage Limit configuration (Loop files count)
* App Localization (English / Russian)

---

## 🚀 Installation & Setup (For Developers)

1. Clone the repository:
   ```bash
   git clone [https://github.com/DmytroSyrovatskyi/MyDashcam.git](https://github.com/DmytroSyrovatskyi/MyDashcam.git)