package app.slate.probe3;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** A badly behaved app. Modes: clear, hostile (one 2.5 MB row and 6,000 more), churn (rewrites its rows for a minute). */
public class PublishActivity extends Activity {
    private static final Uri LIST = Uri.parse("content://android.media.tv/watch_next_program");

    private ContentValues row(String title, long ago) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("type", 0);
        v.put("watch_next_type", 0);
        v.put("intent_uri", "intent:#Intent;component=app.slate.probe3/.PlayActivity;end");
        v.put("poster_art_aspect_ratio", 0);
        v.put("last_engagement_time_utc_millis", System.currentTimeMillis() - ago);
        v.put("internal_provider_id", "bad-" + title.hashCode() + "-" + ago);
        return v;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        final String mode = getIntent().getStringExtra("mode");
        new Thread(() -> {
            try {
                Log.i("SLATEPROBE", "bad deleted " + getContentResolver().delete(LIST, null, null));
                if ("hostile".equals(mode)) {
                    StringBuilder big = new StringBuilder(400_000);
                    for (int i = 0; i < 400_000; i++) big.append('x');
                    // In the future, so that it sorts first. Filled a column at a time: one call cannot carry it all.
                    Uri row = getContentResolver().insert(LIST, row("Big", -3_600_000L));
                    for (String column : new String[] {"title", "poster_art_uri", "thumbnail_uri", "season_display_number", "episode_display_number", "intent_uri"}) {
                        ContentValues v = new ContentValues();
                        v.put(column, big.toString());
                        Log.i("SLATEPROBE", "bad big " + column + " " + getContentResolver().update(row, v, null, null));
                    }
                    for (int b = 0; b < 12; b++) {
                        ContentValues[] batch = new ContentValues[500];
                        for (int i = 0; i < 500; i++) batch[i] = row("Bad " + (b * 500 + i), 1000L + b * 500 + i);
                        getContentResolver().bulkInsert(LIST, batch);
                    }
                    Log.i("SLATEPROBE", "bad hostile published");
                } else if ("churn".equals(mode)) {
                    long end = System.currentTimeMillis() + 60_000L;
                    int n = 0;
                    while (System.currentTimeMillis() < end) {
                        getContentResolver().delete(LIST, null, null);
                        getContentResolver().insert(LIST, row("Churn " + (n++), 0));
                    }
                    Log.i("SLATEPROBE", "bad churn done " + n);
                } else if (!"clear".equals(mode)) {
                    getContentResolver().insert(LIST, row("Bad Film", 30_000L));
                    Log.i("SLATEPROBE", "bad plain published");
                }
            } catch (Throwable t) {
                Log.i("SLATEPROBE", "bad FAILED " + t);
            }
            runOnUiThread(this::finish);
        }).start();
    }
}
