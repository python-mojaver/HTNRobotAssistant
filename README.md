# HTNRobotAssistant

Android control and voice-interface application for the **Timo robot**, developed as part of the **HTN Prime** robotics project.

This repository contains the Android-side integration layer responsible for native microphone capture, WebView ↔ Android communication, robot SDK access, and physical navigation commands.

> **Status:** Active development — stable native microphone + Whisper integration checkpoint available as `timo-android-working-v1`.

---

## Overview

`HTNRobotAssistant` is the Android companion application for the Timo AI Robot platform.

The application bridges the robot's Android environment with the HTN Prime AI backend and provides:

- Native Android microphone capture
- Audio streaming/segmentation for speech recognition
- WebView ↔ Android JavaScript bridge
- Robot SDK integration
- Navigation command execution
- Runtime permission handling
- Connection to a Python/FastAPI + Whisper backend

The project was designed to solve a practical limitation encountered on the robot: microphone access from Android WebView was unreliable. The audio pipeline was therefore moved to Android's native `AudioRecord` API.

---

## Architecture

```text
User Voice
    │
    ▼
Android Native Microphone
(AudioRecord)
    │
    ▼
PCM / WAV Audio
    │
    ▼
JavaScript Bridge
    │
    ▼
WebView UI
    │
    ▼
FastAPI Backend
    │
    ▼
Whisper Speech Recognition
    │
    ├──► AI / Conversational Response
    │
    └──► Robot Command Router
              │
              ▼
         Timo Robot SDK
              │
              ▼
        Physical Navigation
```

Companion backend repository:

**Timo-AI-Robot-Assistant**  
https://github.com/python-mojaver/Timo-AI-Robot-Assistant

---

## Key Features

### Native Voice Capture
The application captures microphone audio using Android's native audio stack instead of relying on browser/WebView microphone APIs.

### Whisper Integration
Captured audio is converted to WAV-compatible data and forwarded to the HTN Prime backend for Persian speech recognition using Whisper.

### JavaScript Bridge
The Android application exposes native functionality to the WebView through a JavaScript interface, including:

```text
startNativeWhisperMic()
stopNativeWhisperMic()
isNativeWhisperMicRunning()
```

### Robot Navigation
The application integrates with the Timo robot SDK and can execute physical navigation commands from the voice/chat interface.

### Runtime Permissions
The application handles Android runtime permissions required for microphone access, WebView interaction, and native audio recording.

---

## Technical Challenge

### WebView Microphone Access

The original voice pipeline used:

```text
WebView
→ navigator.mediaDevices.getUserMedia()
→ MediaRecorder
→ Whisper
```

On the robot, the WebView repeatedly failed to open the audio source even though microphone permission was granted.

The final architecture moved microphone capture to the native Android layer:

```text
Android AudioRecord
→ WAV
→ JavaScript Bridge
→ FastAPI
→ Whisper
```

This architecture successfully restored voice input while keeping the WebView-based interface.

---

## Technology Stack

### Android
- Kotlin
- Android SDK
- `AudioRecord`
- WebView
- JavaScript Interface
- Runtime Permissions
- Gradle Kotlin DSL

### Robotics
- Timo Robot SDK
- Robot navigation API
- Android robot service integration

### AI / Backend
- Python
- FastAPI
- faster-whisper
- Whisper speech recognition
- JavaScript
- AI workflow integration

---

## Project Structure

```text
HTNRobotAssistant/
├── app/
│   ├── libs/
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── com/htnhome/robotassistant/
│   │       │       └── MainActivity.kt
│   │       ├── res/
│   │       └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

---

## Stable Checkpoint

Known working checkpoint:

```text
timo-android-working-v1
```

It represents the first stable version with native microphone capture, Android → WebView audio bridge, Whisper-compatible audio pipeline, and robot integration.

---

## Development Environment

Developed and tested with:

- Android Studio
- Kotlin
- Android Debug Bridge (ADB)
- Physical Timo robot hardware
- FastAPI backend running on a development workstation

During development, ADB reverse forwarding is used:

```bash
adb reverse tcp:8000 tcp:8000
```

The Android application can then access:

```text
http://127.0.0.1:8000
```

---

## Current Development Goals

- Improve Persian speech-recognition accuracy
- Improve voice activity detection
- Strengthen robot command routing
- Integrate conversational AI responses
- Expand navigation destinations
- Add face recognition and personalized greetings
- Add remote robot control and camera monitoring

---

## Companion Project

**Timo-AI-Robot-Assistant**  
https://github.com/python-mojaver/Timo-AI-Robot-Assistant

```text
HTNRobotAssistant
      │
      │ Android / Robot / Native Mic
      ▼
Timo-AI-Robot-Assistant
      │
      │ FastAPI / Whisper / AI
      ▼
HTN Prime
```

---

## Author

**Babak Mojaver**

Python & Backend Developer  
AI & Robotics Developer  
IT Infrastructure & Network Specialist

GitHub: https://github.com/python-mojaver

---

## Project Note

This repository documents an ongoing real-world robotics integration project.

Some robot-specific SDK components, internal configuration, credentials, private endpoints, or proprietary assets may be excluded from the public repository.
