package rw.acplrwa.mis;

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

/**
 * ACPLRWA MIS shell.
 *
 * WHY THIS FILE HAS NFC CODE IN IT
 *
 * This app is a Capacitor wrapper that loads the live site in an Android
 * WebView. Android WebView does NOT implement Web NFC — `NDEFReader` simply
 * does not exist there, even on a handset whose Chrome supports it. So the
 * card-scanning pages cannot sense a card on their own inside this app.
 *
 * Rather than add a third-party NFC plugin (an extra dependency, and some are
 * licence-gated), this uses Android's own NFC Reader Mode directly and hands
 * the tag UID to the page through a single JavaScript global:
 *
 *     window.VTC_onCardScanned("04A224B9")
 *
 * The VTC scanner page already defines that function, so a physical tap
 * behaves identically whether it came from this app, from Chrome's Web NFC, or
 * from a gate reader posting to the API.
 *
 * READER MODE, NOT FOREGROUND DISPATCH — deliberate:
 *   - it suppresses the Android "tag discovered" chirp so the page owns the
 *     feedback (the page vibrates on a good read);
 *   - it stops other apps grabbing the tag while this screen is open;
 *   - it hands over the Tag object directly, so we read the UID off any tag
 *     type rather than only NDEF-formatted ones.
 *
 * That last point matters commercially: this reads the UID of cheap MIFARE
 * Classic 1K cards, which Web NFC generally refuses because Classic is not an
 * NFC Forum tag type. Cards already bought for the gate readers keep working.
 *
 * Nothing here writes to a card. It only ever reads the UID.
 */
public class MainActivity extends BridgeActivity implements NfcAdapter.ReaderCallback {

    private NfcAdapter nfcAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Null on a handset with no NFC radio. Every use below is guarded, so
        // the app runs normally on those devices.
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (nfcAdapter != null) {
            int flags = NfcAdapter.FLAG_READER_NFC_A
                      | NfcAdapter.FLAG_READER_NFC_B
                      | NfcAdapter.FLAG_READER_NFC_F
                      | NfcAdapter.FLAG_READER_NFC_V
                      // We give our own feedback in the page.
                      | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                      // Do not try to parse NDEF; we only want the UID, and
                      // skipping the check makes reads faster and broader.
                      | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

            nfcAdapter.enableReaderMode(this, this, flags, null);
        }

        publishNfcStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Release the radio so other apps (and the lock screen) work normally.
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(this);
        }
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        if (tag == null || tag.getId() == null) {
            return;
        }

        final String uid = bytesToHex(tag.getId());
        if (uid.isEmpty()) {
            return;
        }

        // onTagDiscovered runs on a binder thread; WebView must be touched on
        // the UI thread.
        handler.post(() -> evalJs(
            "window.VTC_onCardScanned && window.VTC_onCardScanned('" + uid + "');"
        ));
    }

    /**
     * Tell the page that native scanning is live, so it can show the right
     * instruction instead of "no NFC plugin installed".
     *
     * onResume can fire before the remote page has finished loading, and a
     * script evaluated against a half-loaded page is simply lost — so this is
     * re-asserted a few times over the first seconds. Cheap, and it removes a
     * race we would otherwise have to hook WebViewClient to close.
     */
    private void publishNfcStatus() {
        final boolean present = nfcAdapter != null;
        final boolean enabled = present && nfcAdapter.isEnabled();

        final String js =
            "window.VTC_NATIVE_NFC = {" +
            "  available: " + present + "," +
            "  enabled: "   + enabled + "," +
            "  source: 'android-reader-mode'" +
            "};" +
            "window.dispatchEvent(new Event('vtc-nfc-status'));";

        for (int delay : new int[] { 0, 1200, 3500 }) {
            handler.postDelayed(() -> evalJs(js), delay);
        }
    }

    private void evalJs(String js) {
        try {
            if (getBridge() == null) {
                return;
            }
            WebView wv = getBridge().getWebView();
            if (wv != null) {
                wv.evaluateJavascript(js, null);
            }
        } catch (Exception e) {
            // A failed status injection must never take the app down.
        }
    }

    /**
     * Uppercase hex, no separators — the same shape the gate readers report,
     * and what Cards::normaliseUid() on the server expects.
     *
     * Built from raw bytes we read ourselves, so the result can only ever be
     * [0-9A-F]. That is why it is safe to interpolate into the JS above.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * A tag tapped while the app was backgrounded arrives as an Intent. Handing
     * it to the bridge keeps behaviour consistent with an in-app tap.
     */
    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent == null) {
            return;
        }

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag != null) {
            onTagDiscovered(tag);
        }
    }
}
