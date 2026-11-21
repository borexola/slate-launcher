package app.slate.probe2;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** A well-behaved app: two ordinary "watch next" entries. Mode "clear" removes them. */
public class PublishActivity extends Activity {
    private static final Uri LIST = Uri.parse("content://android.media.tv/watch_next_program");

    private void add(String title, long ago) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("type", 0);
        v.put("watch_next_type", 0);
        v.put("intent_uri", "intent:#Intent;component=app.slate.probe2/.PlayActivity;end");
        v.put("poster_art_aspect_ratio", 0);
        v.put("last_engagement_time_utc_millis", System.currentTimeMillis() - ago);
        v.put("last_playback_position_millis", 20 * 60000L);
        v.put("duration_millis", 90 * 60000L);
        v.put("internal_provider_id", "good-" + title.hashCode());
        try { Log.i("SLATEPROBE", "good inserted " + getContentResolver().insert(LIST, v)); }
        catch (Throwable t) { Log.i("SLATEPROBE", "good insert FAILED " + t); }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { getContentResolver().delete(LIST, null, null); } catch (Throwable t) { Log.i("SLATEPROBE", "good delete FAILED " + t); }
        if (!"clear".equals(getIntent().getStringExtra("mode"))) { add("Good Film One", 60000); add("Good Film Two", 120000); }
        finish();
    }
}
