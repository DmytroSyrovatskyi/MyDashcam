# REC Dashcam 🚗📹

A smart, lightweight, and fully autonomous dashcam application for Android. Turn your old or current smartphone into a professional digital video recorder (DVR) for your car.

## ✨ Key Features

* **Background Recording:** Records video seamlessly in the background. You can minimize the app, use GPS navigation, or turn off the screen — the recording won't stop.
* **Live Viewfinder (Preview):** A dedicated "PREVIEW" tab allows you to align and calibrate your camera's angle in crisp 1080p *before* you hit record, without wasting storage space.
* **Smart Loop Management:** Automatically deletes old videos when the storage limit is reached, ensuring you never run out of space.
* **File Protection:** Lock crucial videos (e.g., accidents or interesting moments) with a single tap so they won't be overwritten by the loop manager.
* **Thermal Protection:** Automatically monitors the device's temperature. It dynamically lowers the bitrate to prevent overheating and safely stops recording if the battery reaches critical temperatures.
* **Dynamic Bitrate & Auto Exposure:** Adapts to lighting conditions in real-time, pulling details out of dark shadows during night drives.
* **Modern UI:** Clean 3-tab layout (Timeline, Protected, Preview) with a floating control panel.

## ⚙️ Requirements
* **Minimum SDK:** Android 13 (API 33)
* **Target SDK:** Android 14+ (API 34/35) fully supported (with proper Foreground Service permissions).

## 🚀 How to Use
1. Mount your phone on your car's windshield.
2. Open the app and use the **PREVIEW** tab to adjust the camera angle.
3. Select your preferred camera (Wide, Main, or Front), Resolution, and FPS in the settings.
4. Tap the **Green Play Button** to start recording. The app can now be minimized.
5. Tap the **Lock** button during an emergency to secure the current loop file.

## 🛠 Tech Stack
* Kotlin
* CameraX API & Camera2Interop
* MediaRecorder (HEVC/H.265)
* Kotlin Coroutines