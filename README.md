# cctv-local-lubis

Turn an old Android phone into a local CCTV camera with no cloud account and no subscription.

The app captures the rear camera with CameraX, encodes frames as JPEG, and serves them as a
**multipart MJPEG stream over HTTP** on port `8080`. Any browser on the same Wi-Fi network can
watch it, and most NVR / VLC setups can read the stream as a normal IP camera.

## How it works

- `MainActivity` requests camera permission, binds CameraX (`Preview`, `ImageAnalysis`),
 starts a foreground service, acquires a Wi-Fi lock so the radio does not sleep, and shows
 the phone's LAN address so you know where to point the viewer.
- `CameraServer` extends **NanoHTTPD** and listens on `:8080`. It holds only the latest JPEG
 frame in memory and pushes it to every connected client from a cached thread pool, so
 multiple viewers do not multiply camera work.
- Frames are paced at ~30 fps (`FRAME_DELAY_MS = 33`).
- While the camera warms up, a generated placeholder frame (320x240) is served instead of
 dropping the connection.
- A broadcast receiver forwards frames from the analyser to the server.

## Run

1. Open `CCTVStreamer/` in Android Studio.
2. Build and install on the phone you want to use as the camera.
3. Grant camera permission and start the stream.
4. Open the address shown on screen from another device on the same network:

```text
http://<phone-lan-ip>:8080/
```

To use it as an IP camera in VLC: **Media → Open Network Stream →**
`http://<phone-lan-ip>:8080/`.

## Structure

```
CCTVStreamer/app/src/main/java/com/lubis/cctvstreamer/
  MainActivity.kt      permissions, CameraX binding, foreground service, Wi-Fi lock
  CameraServer.kt      NanoHTTPD MJPEG endpoint on port 8080
```

## Notes

- **LAN only.** There is no authentication and no TLS — anyone who can reach port 8080 sees
 the stream. Do not expose it to the internet without putting it behind a reverse proxy
 with auth.
- Keep the phone plugged in and the screen allowed to stay awake; camera streaming drains
 the battery quickly.
- The repository still contains old Gradle caches and two JVM crash logs
 (`hs_err_pid*.log`) from development. They are harmless but not part of the source.

---

## Bahasa Indonesia

**cctv-local-lubis** mengubah ponsel Android lama menjadi kamera CCTV lokal: tanpa akun cloud dan tanpa langganan. Kamera belakang diambil lewat CameraX, frame diubah ke JPEG, lalu disajikan sebagai **stream MJPEG lewat HTTP** di port `8080`.

Selama perangkat berada di jaringan Wi-Fi yang sama, kamera bisa dibuka dari browser mana pun.

---

## License

MIT
