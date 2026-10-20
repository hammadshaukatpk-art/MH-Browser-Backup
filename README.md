## Background media downloads and extractor maintenance — October 20, 2026
- Video/audio downloads now start in an Android foreground data-sync service. Hiding the media panel or leaving the browser does not immediately cancel the transfer; Android may still terminate long-running transfers under system constraints.
- Progress and reported transfer speed are written to the existing Downloads section, refreshed while it is open, and displayed in Android download notifications (notification permission required on Android 13+). Completion and failure are recorded persistently, and the notification opens the Downloads section.
- yt-dlp checks for a newer extractor asynchronously at most once every 24 hours when MH Browser opens, with a manual **Check now** option under Advanced settings. Updates require internet and a compatible upstream release; website changes, login requirements, restrictions and DRM can still break downloads.
- Added concurrent fragment fetching (4) and retry settings (5) where supported. Throughput remains dependent on the source site and network; speed is shown only when reported by the extractor.
- Format selection still opens immediately while supported resolutions/audio choices load asynchronously. Video playback cache is not equivalent to a fully downloaded file.

## Faster media download controls — October 20, 2026
- On recognized YouTube watch/short/live URLs, the floating media download button appears without waiting for the media play event. On other websites, the button appears as soon as the page exposes an HTML5 audio/video element. YouTube SPA URL transitions are detected.
- Tapping the button opens a structured MH Browser themed media download panel immediately. Format inspection continues asynchronously; video resolutions and audio conversion choices appear in the same panel when yt-dlp returns genuine available formats. A slow network or provider restriction can delay the options even though the panel opens instantly.
- YouTube watch pages start a best-effort format prefetch when loaded, and the results are cached for repeat taps during the same browser session. Downloads still fetch media streams separately and cannot reuse all of WebView's playback cache.
- No guarantee of a 1–2 second network extraction, download completion, or access to restricted/DRM media. Other browser features are preserved.

## Media download engine — October 20, 2026
- MH Browser now integrates the open-source yt-dlp Android runtime and FFmpeg to inspect public media pages for actual available resolutions and offer video download or audio extraction to MP3/M4A where supported. Video/audio streams can be merged when required.
- The floating media download button is displayed during media playback and launches a real format inspection. For directly exposed downloadable files, Android Download Manager remains an alternative.
- Completed engine downloads are published to Android Downloads / MH Browser using MediaStore (Android 10+), and listed alongside direct downloads in MH Browser Downloads. On Android 8/9, completed files remain in the app's private external Downloads folder and can be opened via FileProvider.
- In Advanced settings, **Update media download engine** can fetch the latest stable yt-dlp extractor at the user's request. This is useful when a website changes its playback implementation.
- DRM-protected media and unauthorized downloads are not supported. Websites may require login, block extraction, or change their formats. YouTube support is best effort, not guaranteed; this code has been compiled but still requires real-device testing.
- Downloads use a background thread in the app process; keep the app running until completion. The UI displays progress but does not yet provide a persistent foreground download service or resumable downloads.
- Additional libraries increase APK size substantially. Existing MH Browser navigation, bookmarks, tabs, history, settings and private browsing are unchanged.

## October 20, 2026 — Google entry, media button and shortcut preview
- The Google logo now opens the real Google account sign-in URL directly in a **new MH Browser tab**, with no informational popup. This is still an Android WebView and Google can reject sign-in; Google OAuth with a registered client is required for approved native app account identity, and it does not automatically authenticate embedded Gmail or YouTube webpages.
- The media download action is no longer squeezed into the address bar. A visible floating download action appears over an active webpage or fullscreen player when a supported HTML5 media playback event is detected. It offers only directly exposed HTTP(S) sources; segmented/DRM/YouTube streaming quality extraction and MP3 conversion are not implemented.
- Custom Quick Access shortcut forms now show a live website-favicon preview after entering a valid URL. Saved shortcut tiles use the same site favicon with fallback if the icon service is unavailable.
- Private tabs retain DOM storage within their separate WebView data directory, cleared when the private process ends. Media indicators are reset on navigation and favicon updates check their associated domain.

## October 20, 2026 — owner-requested update
- Removed all Picture-in-Picture UI and Android support, including auto-PiP. Kept Android split-screen and normal fullscreen video.
- Google sign-in, Gmail, YouTube and account pages are launched **inside MH Browser tabs**. The normal browser retains cookies and website storage across launches until cleared; Google may still block embedded sign-in and there is no verified Google account synchronization.
- Quick Access tiles fetch original website favicons from Google’s favicon service and display them in rounded tiles, with an initial-letter fallback if the icon cannot be retrieved. This uses a third-party network request for the shortcut domain.


## October 20, 2026 — media and browser UI improvements
- Picture-in-Picture has been fully removed from the app menu, settings, Android activity flags, and auto-enter behavior at the owner’s request. Standard fullscreen HTML5 playback remains supported.
- A media download icon probes directly exposed HTTP(S) HTML5 video/audio sources and presents only the available original sources or source labels. It does **not** extract protected streaming media, promise unsupported quality options, or convert video to audio.
- Download Manager jobs are recorded in a new Downloads page with Today, Previous Day, Week, Monthly, Yearly, All, and date picker filters. Existing downloads made before this update may not appear.
- History now has explicit filter chips, defaulting to Today, and retains per-visit deletion and navigation-stack clearing.
- Private browsing uses a dedicated Android process and WebView data directory on Android 9+. This separates private site storage from regular browsing, and private tabs are not written to the normal history. This is not a guarantee of anonymity from websites, network operators, or the operating system.
- The Google G icon and Login Gmail label appear on both home layouts. Google sign-in, Gmail, YouTube, and account pages now open in **MH Browser tabs**, not an external browser. Standard WebView cookies and DOM storage persist locally. Google may refuse sign-in in embedded Android WebView; Google OAuth account identity and verified login status are **not** implemented. Private profile cookies are separate and cleared at private-session startup/exit.
- Proxy configuration now includes protocol selection and optional session-only credentials. Proxy authentication depends on WebView support and is not guaranteed.
- Network diagnostics show connection type, device local IPv4, device time zone, and device locale. Public IP and approximate location require explicit consent for a third-party ipwho.is lookup.
- About screen identifies Version 1.0 and MH Developers (Masum DEV).

# MH Browser — Android V1.0 (development preview)

Android browser using Android System WebView (Chromium-based). Branded navy/cyan UI with horizontal tab strip, editable home shortcuts, tab switching, Google search, bookmarks and history, dark dialogs, settings and direct-file downloads.

## Implemented
- One home search field (the page address bar appears only on web pages).
- Horizontal tab strip with per-tab close, tab creation, switching and independent WebViews.
- Saved normal-tab URLs and current selection across app relaunches; private tabs are not restored.
- Editable/addable/removable Quick Access shortcuts stored on device.
- Dark themed menu, confirmations, edit dialogs, settings and exit prompt.
- General settings: JavaScript, image loading, zoom, desktop user agent for new tabs and restore tabs.
- Privacy: limited tracker domain blocking, third-party cookie toggle, Safe Browsing and mixed-content blocking; DNT header on direct app navigations.
- Bookmarks, recent URL history and clear browsing data.
- Direct HTTP/HTTPS file downloads using Android Download Manager.
- AndroidX WebKit HTTP proxy override (host and port, if supported by the installed Android System WebView), with apply/disable.
- Android system split-screen eligibility; Picture-in-Picture removed.
- Optional background media continuation where websites and Android allow it.
- Permission-gated website geolocation.

## October 2026 UI and browsing updates
- Picture-in-Picture has been removed at the owner’s request. Fullscreen video is still available.
- The browser can avoid pausing its WebView when background playback is enabled. Sites such as YouTube may still pause playback under their own restrictions; this is not a guarantee of continuous YouTube background audio.
- History now stores page titles, hostnames, visit timestamps and groups by Today, Yesterday, Previous 7 Days, This Month, This Year, and Older. Selected or full deletion also clears the open WebViews' Back/Forward stacks.
- Bookmarks now have a management page with add, open and delete actions.
- The main menu uses branded grouped cards, centered logo and icons. Settings cards are organized and styled.
- Home advertising artwork and **For Advertising, Contact with Us** are placeholders until the owner provides a contact number.
- Home Google account icon opens Google sign-in and services in **new MH Browser WebView tabs**. Cookies persist for normal browsing, but Google can reject WebView sign-in and verified Google account integration needs Google-approved OAuth configuration.
- Desktop site toggle is now per-tab and requests a desktop user agent with wide viewport.

## Not complete / important limitations
- **Incognito is not securely isolated from normal WebView cookies in this preview.** Do not use it for sensitive private sessions.
- Tab URL restoration does not preserve every in-page form, scroll position, playback state, or complete navigation stack.
- HTTP proxy support is unauthenticated only; username/password and SOCKS5 proxies are **not supported**. No VPN or IP/GPS spoofing.
- Android split-screen availability depends on device OS; PiP and background audio depend on Android and individual websites.
- Downloads require a direct file URL; arbitrary streaming video/audio extraction, DRM bypass and resolution picking are not provided.
- DNT headers are advisory and are not guaranteed for every subresource or redirect.
- Tracker blocking is limited, not equivalent to Brave's full protections.
- Not yet tested on physical Android devices. APK compilation does not establish functional correctness.

## Build
JDK 17, Android SDK 35, Gradle 8.9. GitHub Actions builds a debug APK. This is a development preview, not a production privacy browser.
