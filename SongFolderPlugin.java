package hu.petya.genossongbook;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;

@CapacitorPlugin(name = "SongFolder")
public class SongFolderPlugin extends Plugin {

    private File root() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "GenosSongbook");
    }

    private boolean granted() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return getContext().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", granted());
        r.put("path", root().getAbsolutePath());
        call.resolve(r);
    }

    @PluginMethod
    public void ensureFolders(PluginCall call) {
        JSObject r = new JSObject();
        boolean ok = granted();
        if (ok) {
            File root = root();
            root.mkdirs();
            for (String d : new String[]{"Táncdal", "Csárdás", "Rock", "Szerb"}) new File(root, d).mkdirs();
        }
        r.put("granted", ok);
        r.put("path", root().getAbsolutePath());
        call.resolve(r);
    }

    @PluginMethod
    public void requestAccess(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } else {
                getActivity().requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4711);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Engedélykérés sikertelen: " + e.getMessage());
        }
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (!granted()) { call.reject("Nincs hozzáférés a fájlokhoz."); return; }
        try {
            File root = root();
            for (String d : new String[]{"Táncdal", "Csárdás", "Rock", "Szerb"}) new File(root, d).mkdirs();
            JSArray items = new JSArray();
            File[] top = root.listFiles();
            if (top != null) {
                Arrays.sort(top);
                for (File f : top) {
                    if (f.isDirectory()) {
                        File[] sub = f.listFiles();
                        if (sub == null) continue;
                        Arrays.sort(sub);
                        for (File s : sub) addIfTxt(items, s, f.getName());
                    } else {
                        addIfTxt(items, f, "");
                    }
                }
            }
            JSObject r = new JSObject();
            r.put("items", items);
            r.put("path", root.getAbsolutePath());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Olvasási hiba: " + e.getMessage());
        }
    }

    private void addIfTxt(JSArray items, File f, String category) throws Exception {
        if (!f.isFile() || !f.getName().toLowerCase().endsWith(".txt")) return;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        JSObject o = new JSObject();
        o.put("name", f.getName());
        o.put("category", category);
        o.put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
        items.put(o);
    }
}
