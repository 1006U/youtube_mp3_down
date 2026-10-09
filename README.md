# YouTube MP3 Downloader

Based on `kimbaekyu/YoutubeAudioExtractor`.

This repository keeps the original Android/yt-dlp MP3 extraction flow and fixes media-library refresh after download. Newly created or replaced MP3 files are registered with Android `MediaStore` through `MediaScannerConnection`, so Samsung Music can discover them without rebooting the phone.

## Build

Open the project in Android Studio, sync Gradle, and build the `app` module.

> The original repository's Gradle wrapper JAR is a binary file and is not copied by this GitHub text-file migration. Android Studio can sync the project directly. If you need CLI builds, run `gradle wrapper` once locally and commit the generated wrapper files.
