# YouTube MP3 Downloader

Based on `kimbaekyu/YoutubeAudioExtractor`.

This fork keeps the original YouTube audio extraction flow and adds an Android media-library refresh after each successful MP3 download so Samsung Music can discover new tracks without rebooting the device.

## Main fix

After yt-dlp finishes, newly created MP3 files are passed to Android's `MediaScannerConnection`. This updates `MediaStore` immediately instead of waiting for the next reboot/storage scan.

## Build

Open the project in Android Studio and build the `app` module.
