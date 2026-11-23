package app.slate.probe2;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands out the pictures in assets/img, the way a media server's app hands out its posters:
 * content://app.slate.probe2.images/lossy.webp
 *
 * A prefix on the name makes it misbehave, to try out what Slate does about it:
 *   pipe-   sent through a pipe, with no length known beforehand
 *   slow-   15 seconds before anything is handed over
 *   stall-  half of it, then nothing more
 *   big-    the picture over and over, 5 MB in all
 */
public class ImageProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        String asked = uri.getLastPathSegment();
        if (asked == null) throw new FileNotFoundException();
        int dash = asked.indexOf('-');
        final String how = dash > 0 ? asked.substring(0, dash) : "";
        final String name = dash > 0 ? asked.substring(dash + 1) : asked;
        try {
            if (how.equals("slow")) Thread.sleep(15000);
            if (how.isEmpty() || how.equals("slow")) return getContext().getAssets().openFd("img/" + name);
            final byte[] bytes;
            try (InputStream in = getContext().getAssets().open("img/" + name)) {
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                for (int n; (n = in.read(buffer)) > 0; ) all.write(buffer, 0, n);
                bytes = all.toByteArray();
            }
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if (how.equals("stall")) {
                        out.write(bytes, 0, bytes.length / 2);
                        out.flush();
                        Thread.sleep(60000);
                    } else if (how.equals("big")) {
                        for (int sent = 0; sent < 5 * 1024 * 1024; sent += bytes.length) out.write(bytes);
                    } else {
                        out.write(bytes);
                    }
                } catch (IOException | InterruptedException e) {
                    // The reader went away.
                }
            }).start();
            return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (IOException | InterruptedException e) {
            throw new FileNotFoundException(String.valueOf(e));
        }
    }

    @Override public String getType(Uri uri) { return "image/*"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
