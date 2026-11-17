# Jarvis
- `jarvis.py` - the assistant server (run inside Termux)
- `app/` - Android app shell (WebView) built automatically by GitHub Actions

## Build the APK
Push to GitHub -> Actions tab -> "Build APK" -> download artifact `jarvis-apk` -> unzip -> install app-debug.apk

## Termux setup
pkg install python ollama termux-api play-audio ffmpeg
ollama pull qwen2.5:7b && ollama pull nomic-embed-text
cp jarvis.py ~/ && python ~/jarvis.py
