package hu.petya.genossongbook;

import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SongFolderPlugin.class);
        super.onCreate(savedInstanceState);
        WebView wv = getBridge().getWebView();
        // Web MIDI (inkl. SysEx) erlauben
        wv.setWebChromeClient(new BridgeWebChromeClient(getBridge()) {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
        });
    }
}
