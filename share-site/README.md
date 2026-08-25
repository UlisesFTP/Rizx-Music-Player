# Share domain for Rizx links

What the host behind `RIZX_SHARE_BASE_URL` has to serve so that a scanned QR or a tapped share link
opens **directly in Rizx** on Android 12+ (Android App Links), and shows a one-tap landing page to
anyone without the app.

Android only opens an `https` link straight into an app when the app is *verified* for that host: the
host must serve `/.well-known/assetlinks.json` listing the app's signing certificates. The Supabase
project domain cannot serve files at its root, which is why the share host has to be a domain you
control.

## Files

| Path | Purpose |
|---|---|
| `.well-known/assetlinks.json` | App Links statement: `fm.rizx.player` (release-signed and the debug-signed `releaseTest`) and `fm.rizx.player.debug`. Certificate fingerprints are public by design. |
| `s/index.html` | Landing page for `https://<host>/s/<token>`: "Open in Rizx" (`intent://` → `rizx://share/<token>`), download link, playlist preview fetched from the share function. |
| `404.html` | The same page, for hosts that cannot rewrite `/s/<token>` to `s/index.html` (GitHub Pages serves it for unknown paths). |
| `_redirects` | Rewrite rule for Cloudflare Pages / Netlify. |
| `.nojekyll` | Makes GitHub Pages serve the dot-directory `.well-known/` as-is. |

## Deploy

Any static host works: a GitHub Pages **user site** (a repository named `<user>.github.io` — a project
site cannot serve `/.well-known/` at the host root), Cloudflare Pages, Netlify, Vercel, or a plain web
server. `assetlinks.json` must be served over HTTPS with status 200, content type `application/json`
and no redirects.

1. Publish this folder at the root of the host.
2. Check it: `https://<host>/.well-known/assetlinks.json` returns the JSON above. Google's checker:
   `https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://<host>&relation=delegate_permission/common.handle_all_urls`
3. Point both sides at the new host — they must agree to the character:
   - app: `RIZX_SHARE_BASE_URL=https://<host>/s` in `~/.gradle/gradle.properties`, then rebuild;
   - function: Supabase secret `SHARE_BASE_URL=https://<host>/s` for `playlist-shares`.

   The app keeps reading documents from the function (`SUPABASE_URL/functions/v1/playlist-shares/<token>`);
   only the address printed on links and QR codes changes.
4. On a device with the new build: `adb shell pm get-app-links fm.rizx.player` shows the host as
   `verified` once Android has checked it (it does so in the background after install; give it a minute).

Until the host exists, Android 8–11 offer "Open with Rizx" for share links, Android 12+ open the browser
— where the function's landing page offers "Open in Rizx" — and any phone can enable the link by hand
under *App info › Open by default › Add link*.
