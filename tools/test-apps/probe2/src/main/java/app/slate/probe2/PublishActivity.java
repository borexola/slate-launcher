package app.slate.probe2;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * A well-behaved app: two ordinary "watch next" entries. Mode "clear" removes them. Mode "demo"
 * publishes four entries with posters instead (Blender open films, whose posters are CC BY
 * and hosted by Wikimedia Commons); the README's screenshot was made with it. Wikimedia limits
 * how fast one address may fetch, so more entries than this tend to come back without a poster.
 *
 * Mode "demo" with a "list" extra publishes that list instead, most recent first:
 * `--es list "Title|https://host/poster.jpg;Another title|https://host/other.jpg"`.
 */
public class PublishActivity extends Activity {
    private static final Uri LIST = Uri.parse("content://android.media.tv/watch_next_program");
    private static final String COMMONS = "https://upload.wikimedia.org/wikipedia/commons/thumb/";

    private void add(String title, long ago) {
        add(title, ago, null, 20);
    }

    private void add(String title, long ago, String poster, int minutesWatched) {
        add(title, ago, poster == null ? null : COMMONS + poster, minutesWatched, 5);
    }

    private void add(String title, long ago, String poster, int minutesWatched, int shape) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("type", 0);
        v.put("watch_next_type", 0);
        v.put("intent_uri", "intent:#Intent;component=app.slate.probe2/.PlayActivity;end");
        if (poster != null) v.put("poster_art_uri", poster);
        v.put("poster_art_aspect_ratio", poster == null ? 0 : shape);
        v.put("last_engagement_time_utc_millis", System.currentTimeMillis() - ago);
        v.put("last_playback_position_millis", minutesWatched * 60000L);
        v.put("duration_millis", 90 * 60000L);
        v.put("internal_provider_id", "good-" + title.hashCode());
        try { Log.i("SLATEPROBE", "good inserted " + getContentResolver().insert(LIST, v)); }
        catch (Throwable t) { Log.i("SLATEPROBE", "good insert FAILED " + t); }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { getContentResolver().delete(LIST, null, null); } catch (Throwable t) { Log.i("SLATEPROBE", "good delete FAILED " + t); }
        String mode = getIntent().getStringExtra("mode");
        String list = getIntent().getStringExtra("list");
        if ("demo".equals(mode) && list != null) {
            // Posters given this way are taken to be 2:3, the usual shape of a film poster.
            int[] watched = {62, 35, 12, 48, 75, 25};
            String[] entries = list.split(";");
            for (int i = 0; i < entries.length; i++) {
                String[] parts = entries[i].split("\\|", 2);
                if (parts.length == 2) add(parts[0].trim(), 60000L * (i + 1), parts[1].trim(), watched[i % watched.length], 4);
            }
        } else if ("demo".equals(mode)) {
            add("Sintel", 60000, "8/8f/Sintel_poster.jpg/500px-Sintel_poster.jpg", 62);
            add("Spring", 120000, "2/25/Spring2019AlphaPosterBlender.jpg/500px-Spring2019AlphaPosterBlender.jpg", 35);
            add("Big Buck Bunny", 180000, "c/c5/Big_buck_bunny_poster_big.jpg/500px-Big_buck_bunny_poster_big.jpg", 12);
            add("Cosmos Laundromat", 240000, "c/c5/CosmosLaundromatPoster.jpg/500px-CosmosLaundromatPoster.jpg", 48);
        } else if (!"clear".equals(mode)) {
            add("Good Film One", 60000);
            add("Good Film Two", 120000);
        }
        finish();
    }
}
