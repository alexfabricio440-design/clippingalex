package com.clippingalex.clipcaptions;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.clippingalex.clipcaptions.core.Box;
import com.clippingalex.clipcaptions.core.FaceTrack;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.List;

/** Busca rostros a lo largo del video, dentro del telefono (ML Kit). */
public final class FaceTracker {
    public interface Progress { void on(float fraction); }

    private FaceTracker() { }

    public static FaceTrack track(Context ctx, Uri video, double durationSec,
                                  int upW, int upH, Progress progress) throws Exception {
        double step = Math.max(0.33, durationSec / 150.0);
        FaceTrack track = new FaceTrack(Math.max(1.0, step * 1.25));
        FaceDetector det = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.08f)
                .build());
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, video);
            int tw = upW >= upH ? 480 : Math.max(2, Math.round(480f * upW / upH));
            int th = upW >= upH ? Math.max(2, Math.round(480f * upH / upW)) : 480;
            int n = (int) Math.floor(durationSec / step) + 1;
            for (int i = 0; i < n; i++) {
                double t = Math.min(i * step, Math.max(0, durationSec - 0.05));
                Bitmap b = r.getScaledFrameAtTime((long) (t * 1e6),
                        MediaMetadataRetriever.OPTION_CLOSEST, tw, th);
                List<Box> boxes = new ArrayList<>();
                if (b != null) {
                    List<Face> faces = Tasks.await(det.process(InputImage.fromBitmap(b, 0)));
                    for (Face f : faces) {
                        Rect bb = f.getBoundingBox();
                        double x = Math.max(0, bb.left / (double) b.getWidth());
                        double y = Math.max(0, bb.top / (double) b.getHeight());
                        double w = Math.min(1 - x, bb.width() / (double) b.getWidth());
                        double h = Math.min(1 - y, bb.height() / (double) b.getHeight());
                        if (w * h > 0.0004) boxes.add(new Box(x, y, w, h));
                    }
                    b.recycle();
                }
                track.add(t, boxes);
                if (progress != null) progress.on((i + 1f) / n);
            }
        } finally {
            try { r.release(); } catch (Exception ignored) { }
            det.close();
        }
        return track;
    }
}
