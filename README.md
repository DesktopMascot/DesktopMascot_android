# Desktop Mascot

An animated desktop-style mascot for Android.

Desktop Mascot lets you keep a small animated character on top of your Android apps. The mascot can sit, walk around, and rest, giving your device a more playful desktop-pet style experience.

## Features

- 🐾 Animated mascot overlay
- 🚶 Walking animations
- 🪑 Sitting and resting animations
- 🎨 Use your own mascot images
- 🖼️ Support for multiple animation frames
- 🫁 Optional breathing animation
- ⚙️ Customizable movement settings
- 📱 Designed for Android

## Custom Mascots

Desktop Mascot supports using your own PNG images instead of the built-in mascot.

Custom images can be provided for:

- Sit
- Walk Left
- Walk Right
- Rest

Multiple images can be selected for walking animations, allowing you to create your own frame-by-frame animation.

The app stores imported custom mascot images in its private application storage.

## Permissions

Desktop Mascot uses Android's overlay functionality so that the mascot can appear above other applications.

Depending on your Android version and configuration, Android may ask you to grant permission to display the mascot over other apps.

The app does not require an account or a cloud service to operate.

## Building

This project is an Android application built with Kotlin and Gradle.

### Requirements

- Android Studio

Clone the repository:

```bash
git clone https://github.com/ekandr/desktopmascot.git
cd desktopmascot
```

Then open the project in Android Studio and allow Gradle to synchronize the project.

You can also build the application from the command line using the Gradle wrapper:

```bash
./gradlew assembleDebug
```

The resulting APK will be placed under:

```text
app/build/outputs/apk/
```

## Project Structure

```text
app/
├── src/
│   └── main/
│       ├── java/
│       └── res/
├── build.gradle.kts
└── ...
```

The application uses Android services to manage the mascot overlay and its animation.

## License

### Source Code

The source code of Desktop Mascot is licensed under the MIT License.

See [`LICENSE`](LICENSE) for the complete license text.

### PNG Assets

The PNG images included with the project are **not covered by the source-code MIT License**.

See [`LICENSE-ASSETS`](LICENSE-ASSETS) for the terms applicable to the included artwork and other image assets.
