// Fiddler Classic / CustomRules.js
// Merge the marked block into the existing Handlers.OnBeforeRequest function.
// It redirects only the two static bootstrap documents; the returned server list
// points the game protocol at 127.0.0.1. No production login/game request is copied.

static function OnBeforeRequest(oSession: Session) {
    // STELLA_SORA_LOCAL_BEGIN
    if (oSession.HostnameIs("nova-static.stargazer-games.com")) {
        if (oSession.PathAndQuery.StartsWith("/meta/serverlist.html")) {
            oSession.fullUrl = "http://127.0.0.1:18080/meta/serverlist.html";
            return;
        }

        if (oSession.PathAndQuery.StartsWith("/meta/win.html")) {
            oSession.fullUrl = "http://127.0.0.1:18080/meta/win.html";
            return;
        }
    }
    // STELLA_SORA_LOCAL_END

    // Keep any pre-existing OnBeforeRequest rules below this line.
}
