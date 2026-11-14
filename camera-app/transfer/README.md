# :transfer: the Wi-Fi transfer library

A camera's photos to a browser on a computer or phone, over HTTPS, switched on by hand. GC Gallery hosts it; another app (a camera, another device's gallery) can host it the same way. GPL-3.0-or-later.

## What it does

- A foreground service (`dataSync`) runs a small HTTPS server on the Wi-Fi address only (port 8443), and answers mDNS for `<name>.local`.
- Away from any Wi-Fi it starts a local-only hotspot (no internet behind it) and shows its name, password and QR codes.
- It stops by itself after 10 minutes without requests or bytes sent, when the network goes, or from the notification, the Quick Settings tile or its screen. While on it holds a partial wake lock, a Wi-Fi lock and a multicast lock; when off it holds nothing.
- The page (`assets/web/index.html`, one file):
  - a grid by day;
  - a JPEG + RAW / JPEG / RAW choice;
  - "Copy new" into a folder picked once (File System Access API in Chrome and Edge), skipping what is there;
  - a full-screen viewer with the details panel;
  - moving photos to the host's trash.
- Access is open on the local network unless the user sets a password on the device (`TransferActivity`); a browser types it once and keeps a 400-day cookie.
- The certificate is self-signed, made once per name.

## Hosting it

1. Depend on the module: `implementation(project(":transfer"))`. It brings `:common` for its colours and styles.
2. Make the `Application` implement `TransferHost`:
   - `photoSource`: your `PhotoSource` (below);
   - `transferName` (optional): the name on the network. It defaults to the model, lower case without "ek-", e.g. `gc100`;
   - `transferIcon` (optional): the notification icon.
3. Declare the Quick Settings tile in your own manifest, as a subclass of `TransferTileService`, so its component name is yours (a ROM can list it among the default tiles):

   ```xml
   <service
       android:name=".transfer.TransferTile"
       android:exported="true"
       android:icon="@drawable/ic_wifi_transfer"
       android:label="@string/transfer_title"
       android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
       <intent-filter>
           <action android:name="android.service.quicksettings.action.QS_TILE" />
       </intent-filter>
       <meta-data android:name="android.service.quicksettings.TOGGLEABLE_TILE" android:value="true" />
   </service>
   ```

4. Open `TransferActivity` from wherever your app offers the transfer. The library's manifest declares it, the service, the tile's invisible start activity and the permissions.

The screen uses the app's theme and the `:common` colour tokens.

## PhotoSource

Every call comes from the server's threads and may block. Only what `items()` lists is served.

| Call | What the host returns |
|---|---|
| `start()` | (main thread, optional) start loading the list |
| `items()` | the photos newest first, as `TransferItem` (file, dates, size, folder, `"sd"`/`"internal"`, an optional paired RAW); the **same list instance** while nothing changed |
| `thumbnail(item)` | a small upright JPEG file, about 256 px |
| `open(file)` | a `ParcelFileDescriptor` to stream the file |
| `preview(item)` | a JPEG for an item that is not a JPEG (a DNG's embedded preview), or null; JPEGs are shown as they are |
| `info(item, context)` | the details panel as `TransferInfo`, localised with the given context |
| `trash(items)` | move them, with their RAW, to the host's trash; false if not allowed |

`TransferFile.key` carries the host's own object, so the host can map files back without a lookup.

## Needs from the system

- `NEARBY_WIFI_DEVICES` (`neverForLocation`) for the hotspot, and `POST_NOTIFICATIONS` for the notification. GC Gallery gets both from the ROM's default permissions and never asks. Without them the transfer still runs on Wi-Fi, without the hotspot or the notification.
- Trashing another app's photos with no activity on screen needs write access to them. GC Gallery holds the SYSTEM_GALLERY role for that.
- On LineageOS, an app first installed as a normal app *without* INTERNET is put on the restricted-networking denylist, and keeps it when an update adds INTERNET. The server then gets no traffic. The user turns on "Network access" in the app's info, and the LineageOS firewall icon in the status bar is the hint. System apps, as GC Gallery is in the ROM, are not affected.

## Kept across updates

The names below must not change, or users lose their password, their browsers' sessions or their accepted certificate:

- the SharedPreferences `transfer` (the password hash, its salt, the session tokens);
- the file `transfer-tls.p12` in the app's files directory.

## Code map

- `TransferService`: the run: network or hotspot, locks, server and mDNS, idle stop, notification.
- `Transfer`: the state for the screen, the notification and the tile.
- `WebServer`: HTTPS, the routes, streaming, Range, the POST guard.
- `TransferAuth`: the optional password (PBKDF2) and the session tokens.
- `TlsCert`: the self-signed certificate, with hand-built DER.
- `MdnsResponder`: A records for `<name>.local`.
- `QrCode`: the QR encoder for the hotspot's codes.
- `TransferTileService`, `TransferStartActivity`: the tile and its start.
- `TransferActivity`: the screen.
- `PhotoSource`: `PhotoSource`, `TransferItem`, `TransferFile`, `TransferInfo` and `TransferHost`.
