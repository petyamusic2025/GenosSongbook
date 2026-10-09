package hu.petya.genossongbook;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
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

    private File safe(String rel) throws Exception {
        if (rel == null || rel.contains("..") || rel.startsWith("/")) throw new Exception("Érvénytelen útvonal");
        return new File(root(), rel);
    }

    @PluginMethod
    public void writeText(PluginCall call) {
        try {
            if (!granted()) { call.reject("Nincs hozzáférés."); return; }
            File f = safe(call.getString("path")); f.getParentFile().mkdirs();
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(call.getString("text", "").getBytes("UTF-8")); o.close();
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void writeBase64(PluginCall call) {
        try {
            if (!granted()) { call.reject("Nincs hozzáférés."); return; }
            File f = safe(call.getString("path")); f.getParentFile().mkdirs();
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(Base64.decode(call.getString("data", ""), Base64.DEFAULT)); o.close();
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    private byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    @PluginMethod
    public void readText(PluginCall call) {
        try {
            if (!granted()) { call.reject("Nincs hozzáférés."); return; }
            File f = safe(call.getString("path"));
            JSObject r = new JSObject();
            r.put("exists", f.isFile());
            if (f.isFile()) r.put("text", new String(readAll(f), "UTF-8"));
            call.resolve(r);
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void readBase64(PluginCall call) {
        try {
            if (!granted()) { call.reject("Nincs hozzáférés."); return; }
            File f = safe(call.getString("path"));
            JSObject r = new JSObject();
            r.put("exists", f.isFile());
            if (f.isFile()) r.put("data", Base64.encodeToString(readAll(f), Base64.NO_WRAP));
            call.resolve(r);
        } catch (Exception e) { call.reject(e.getMessage()); }
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

    @PluginMethod
    public void scanMedia(PluginCall call) {
        if (!granted()) { call.reject("Nincs hozzáférés a fájlokhoz."); return; }
        try {
            File root = root();
            JSArray items = new JSArray();
            File[] top = root.listFiles();
            if (top != null) {
                Arrays.sort(top);
                for (File f : top) {
                    if (f.isDirectory()) {
                        if (f.getName().equals("icons")) continue;
                        File[] sub = f.listFiles();
                        if (sub == null) continue;
                        Arrays.sort(sub);
                        for (File s : sub) addIfMedia(items, s, f.getName() + "/" + s.getName());
                    } else {
                        addIfMedia(items, f, f.getName());
                    }
                }
            }
            JSObject r = new JSObject();
            r.put("items", items);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Olvasási hiba: " + e.getMessage());
        }
    }

    private void addIfMedia(JSArray items, File f, String rel) throws Exception {
        if (!f.isFile()) return;
        String n = f.getName().toLowerCase();
        if (!(n.endsWith(".mp3") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".pdf"))) return;
        JSObject o = new JSObject();
        o.put("name", f.getName());
        o.put("rel", rel);
        items.put(o);
    }

    // ===== Natív MP3 lejátszás (nagy fájlokhoz is, a WebView memóriája nélkül) =====
    private MediaPlayer mp = null;
    private boolean mpEnded = false;

    private synchronized void releasePlayer() {
        if (mp != null) { try { mp.stop(); } catch (Exception e) {} try { mp.release(); } catch (Exception e) {} mp = null; }
        mpEnded = false;
    }

    @PluginMethod
    public synchronized void audioOpen(PluginCall call) {
        try {
            if (!granted()) { call.reject("Nincs hozzáférés."); return; }
            File f = safe(call.getString("path"));
            if (!f.isFile()) { call.reject("Nincs ilyen fájl."); return; }
            releasePlayer();
            MediaPlayer m = new MediaPlayer();
            m.setDataSource(f.getAbsolutePath());
            m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer x) { mpEnded = true; }
            });
            m.prepare();
            mp = m;
            JSObject r = new JSObject();
            r.put("duration", m.getDuration());
            call.resolve(r);
        } catch (Exception e) { releasePlayer(); call.reject("Lejátszási hiba: " + e.getMessage()); }
    }

    @PluginMethod
    public synchronized void audioPlay(PluginCall call) {
        try { if (mp != null) { if (mpEnded) { mp.seekTo(0); mpEnded = false; } mp.start(); } call.resolve(); } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public synchronized void audioPause(PluginCall call) {
        try { if (mp != null && mp.isPlaying()) mp.pause(); call.resolve(); } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public synchronized void audioSeek(PluginCall call) {
        try { if (mp != null) { Integer ms = call.getInt("ms", 0); mp.seekTo(ms); mpEnded = false; } call.resolve(); } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public synchronized void audioStatus(PluginCall call) {
        try {
            JSObject r = new JSObject();
            if (mp == null) { r.put("open", false); r.put("playing", false); r.put("pos", 0); r.put("duration", 0); }
            else { r.put("open", true); r.put("playing", mp.isPlaying()); r.put("pos", mp.getCurrentPosition()); r.put("duration", mp.getDuration()); r.put("ended", mpEnded); }
            call.resolve(r);
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public synchronized void audioStop(PluginCall call) {
        releasePlayer(); call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        releasePlayer();
        super.handleOnDestroy();
    }
}
