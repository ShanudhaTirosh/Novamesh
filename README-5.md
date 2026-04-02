# 🌐 NovaMesh — Smart Hotspot Router OS

> Transform your Android device into a fully-featured, admin-controlled portable router with a cyberpunk-style web dashboard.

---

## 📁 Project Structure

```
HotspotRouterOS/
├── android/
│   └── app/
│       ├── build.gradle
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   └── webui/
│           │       └── dashboard.html          ← Copy web-ui/dashboard.html here
│           └── java/com/novamesh/hotspot/
│               ├── MainActivity.kt
│               ├── HotspotService.kt           ← Foreground service
│               ├── HotspotManager.kt           ← WiFi/hotspot control
│               ├── LocalWebServer.kt           ← NanoHTTPD REST server
│               ├── FirebaseManager.kt          ← Firebase integration
│               └── HotspotConfigStore.kt       ← Local config persistence
└── web-ui/
    └── dashboard.html                          ← Complete SPA dashboard
```

---

## 🚀 Quick Start

### Step 1 — Firebase Setup

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Create project: **NovaMesh**
3. Add an **Android app** with package `com.novamesh.hotspot`
4. Download `google-services.json` → place in `android/app/`
5. Enable these Firebase features:
   - **Authentication** → Email/Password provider
   - **Firestore** → Start in test mode
   - **Realtime Database** → Start in test mode
6. Create admin user in Firebase Auth console

### Step 2 — Build the Android App

```bash
# Open in Android Studio
File → Open → HotspotRouterOS/android/

# Copy web UI into assets
mkdir -p app/src/main/assets/webui/
cp ../../web-ui/dashboard.html app/src/main/assets/webui/

# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 3 — Run

1. Install APK on your secondary Android phone
2. Grant all permissions when prompted
3. The foreground service starts automatically
4. Enable the phone's hotspot (or let the app do it)
5. Connect your main device to `NovaMesh-5G`
6. Open browser → `http://192.168.43.1:8080`
7. Log in with Firebase credentials

---

## ✅ Feature Matrix — Root vs No Root

| Feature | No Root | Root/System |
|---|---|---|
| Enable/disable hotspot | ✅ (API < 30 via reflection) | ✅ |
| Set SSID / password | ✅ (API < 29) | ✅ |
| Read connected devices | ✅ (ARP table) | ✅ |
| Block device (MAC) | ⚠️ Config only, not enforced | ✅ iptables |
| Per-device speed limit | ❌ | ✅ tc + iptables |
| Custom DNS (system-wide) | ⚠️ VPN trick (see below) | ✅ iptables NAT |
| Guest network isolation | ⚠️ App-level only | ✅ netns |
| Hotspot on API 30+ | ⚠️ Needs manual enable | ✅ |
| Web UI dashboard | ✅ Always works | ✅ |
| Firebase sync | ✅ Always works | ✅ |

### ⚠️ Android 10+ Hotspot Control

Android 10 (API 29) removed the `setWifiApEnabled` reflection API. Options:

**Option A (Recommended for non-root):** Guide user to enable hotspot manually, then the app manages connected devices, serves the dashboard, and stores configs.

**Option B — VPN DNS Override:**
```kotlin
// Use VpnService to intercept all DNS traffic (no root needed)
// Redirect DNS queries to your chosen server
// See VpnDnsManager.kt (optional addon)
class VpnDnsManager : VpnService() {
    fun startDNSRedirect(dnsServer: String) {
        val builder = Builder()
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer(dnsServer)
        // ... establish tunnel
    }
}
```

**Option C — Root:** Full iptables control, see `HotspotManager.kt`

---

## 🌐 Web Dashboard Access

Once your secondary phone's hotspot is active and the app is running:

| URL | Purpose |
|---|---|
| `http://192.168.43.1:8080` | Main dashboard |
| `http://192.168.43.1:8080/api/status` | JSON status |
| `http://192.168.43.1:8080/api/devices` | Connected devices JSON |

### REST API Reference

```
GET  /api/status              → Hotspot status, config summary
GET  /api/devices             → Live connected device list

POST /api/hotspot/enable      → Start hotspot (requires X-Auth-Token)
POST /api/hotspot/disable     → Stop hotspot
POST /api/hotspot/config      → Update SSID/password/band
     Body: { "ssid": "...", "password": "...", "band": 5 }

POST /api/device/block        → Block/unblock device
     Body: { "mac": "AA:BB:CC:DD:EE:FF", "blocked": true }

POST /api/device/limit        → Set speed limit
     Body: { "ip": "192.168.43.24", "limit_kbps": 5120 }

POST /api/dns                 → Change DNS server
     Body: { "primary": "1.1.1.1", "secondary": "1.0.0.1" }
```

**Authentication:** All POST endpoints require:
```
X-Auth-Token: <token from app>
```
The token is generated on first launch and stored in SharedPreferences.

---

## 🔐 Firebase Security Rules

### Firestore Rules
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{userId}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```

### Realtime Database Rules
```json
{
  "rules": {
    "realtime": {
      "$uid": {
        ".read": "auth != null && auth.uid == $uid",
        ".write": "auth != null && auth.uid == $uid"
      }
    }
  }
}
```

---

## ⚙️ Configuration Files

### `google-services.json`
Place in `android/app/google-services.json` — download from Firebase Console.

### Default Config (HotspotConfigStore)
```kotlin
ssid          = "NovaMesh-5G"
password      = "NovaSecure2024"
band          = 0          // auto
maxDevices    = 8
dnsPrimary    = "8.8.8.8"
dnsSecondary  = "8.8.4.4"
guestEnabled  = false
aclMode       = "open"
```

---

## 🔋 Battery Optimization

The app uses:
- **Foreground Service** — prevents background kill
- **PARTIAL_WAKE_LOCK** — prevents CPU sleep
- **START_STICKY** — restarts if killed

To prevent Android from killing the service:
1. Go to **Settings → Battery → Battery Optimization**
2. Find **NovaMesh** → Set to **"Don't optimize"**
3. Or prompt from app: `startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))`

---

## 📦 Dependencies

| Library | Purpose | Version |
|---|---|---|
| NanoHTTPD | Local web server | 2.3.1 |
| Firebase Auth | Admin authentication | BOM 33.1.2 |
| Firebase Firestore | Config persistence | BOM 33.1.2 |
| Firebase Realtime DB | Live device sync | BOM 33.1.2 |
| OkHttp | HTTP client | 4.12.0 |
| Kotlin Coroutines | Async operations | 1.8.0 |

---

## 🚧 Known Limitations

1. **Android 10+ tethering** — Full hotspot control requires root or manual enable
2. **Bandwidth limiting** — Only works with root (tc/iptables)
3. **Device naming** — Android only exposes IP/MAC; hostnames come from reverse DNS (may fail)
4. **5 GHz only** — Band selection depends on hardware support
5. **WPA3** — Only available on Android 10+ with capable hardware
6. **HTTPS on local server** — Self-signed cert is easy; browser will warn. Use for production with real cert via reverse proxy (Caddy/nginx on a Pi bridged to the Android hotspot).

---

## 🔮 Bonus Features — How to Add

### Captive Portal
Add a redirect rule in `LocalWebServer`: intercept all HTTP requests from unauthenticated clients and redirect to `/captive-portal`. Store session tokens in memory.

### AI Assistant Integration
```kotlin
// In LocalWebServer.kt — add endpoint:
uri == "/api/ai" -> handleAIQuery(session)

// Use Claude API or Gemini API
private fun handleAIQuery(session: IHTTPSession): Response {
    val body = readBody(session)
    val query = JSONObject(body).getString("query")
    // Call Claude API → return response
}
```

### OTA Config Updates
Firebase Remote Config can push new default settings without an app update.

### Traffic Graphs
The web dashboard already includes a live bandwidth chart. For historical data, log bandwidth samples to Firestore every 60 seconds from the polling thread in `HotspotService`.

---

## 🏗️ Architecture Diagram

```
┌─────────────────────────────────────────────────────┐
│                Secondary Android Phone               │
│                                                      │
│  ┌──────────────┐     ┌─────────────────────────┐   │
│  │ HotspotService│────▶│   HotspotManager         │   │
│  │ (Foreground) │     │  WifiManager/iptables    │   │
│  └──────┬───────┘     └─────────────────────────┘   │
│         │                                            │
│         │     ┌─────────────────────────────────┐   │
│         ├────▶│   LocalWebServer (NanoHTTPD:8080)│   │
│         │     │   Serves dashboard.html + REST  │   │
│         │     └─────────────┬───────────────────┘   │
│         │                   │                        │
│         │     ┌─────────────▼───────────────────┐   │
│         └────▶│   FirebaseManager                │   │
│               │   Auth + Firestore + RealtimeDB  │   │
│               └─────────────────────────────────┘   │
│                              │                       │
│   WiFi Hotspot ──────────────┼─────────────────────│
└──────────────────────────────┼──────────────────────┘
                               │ Firebase Cloud
                               ▼
                    ┌──────────────────┐
                    │  Firebase        │
                    │  Auth / Firestore│
                    │  Realtime DB     │
                    └──────────────────┘
                               ▲
                    Connected Device Browser
                    http://192.168.43.1:8080
```

---

## 📝 License

MIT License — Free to use, modify, and distribute.

---

*Built with ❤️ — NovaMesh v2.1.0*
