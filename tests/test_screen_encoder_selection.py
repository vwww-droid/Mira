"""Exercise the production AVC encoder selector on a host JVM with Android service stubs."""
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ScreenEncoderSelectionTest(unittest.TestCase):
    def test_selection_order_deduplication_and_cache_fallbacks(self):
        stubs = {
            "android/content/Context.java": """
                package android.content;
                public abstract class Context {
                    public static final int MODE_PRIVATE = 0;
                    public Context getApplicationContext() { return this; }
                    public abstract SharedPreferences getSharedPreferences(String name, int mode);
                }
            """,
            "android/content/SharedPreferences.java": """
                package android.content;
                public interface SharedPreferences {
                    int getInt(String key, int fallback);
                    String getString(String key, String fallback);
                    boolean getBoolean(String key, boolean fallback);
                    Editor edit();
                    interface Editor {
                        Editor putInt(String key, int value);
                        Editor putString(String key, String value);
                        Editor putBoolean(String key, boolean value);
                        void apply();
                    }
                }
            """,
            "android/os/Build.java": """
                package android.os;
                public final class Build {
                    public static String MANUFACTURER = "unknown";
                    public static String MODEL = "unknown";
                    public static final class VERSION { public static int SDK_INT = 0; }
                }
            """,
            "android/util/Log.java": """
                package android.util;
                public final class Log {
                    public static int i(String tag, String message) { return 0; }
                    public static int w(String tag, String message, Throwable error) { return 0; }
                }
            """,
            "android/util/Range.java": """
                package android.util;
                public final class Range<T extends Comparable<? super T>> {
                    private final T lower;
                    private final T upper;
                    public Range(T lower, T upper) { this.lower = lower; this.upper = upper; }
                    public T getLower() { return lower; }
                    public T getUpper() { return upper; }
                }
            """,
            "android/media/MediaCodecInfo.java": """
                package android.media;
                import android.util.Range;
                public class MediaCodecInfo {
                    private final String name;
                    private final boolean encoder;
                    private final String[] types;
                    private final CodecCapabilities capabilities;
                    public MediaCodecInfo(String name, boolean encoder, String[] types, CodecCapabilities capabilities) {
                        this.name = name; this.encoder = encoder; this.types = types; this.capabilities = capabilities;
                    }
                    public String getName() { return name; }
                    public boolean isEncoder() { return encoder; }
                    public String[] getSupportedTypes() { return types; }
                    public CodecCapabilities getCapabilitiesForType(String type) { return capabilities; }
                    public static final class CodecProfileLevel {
                        public static final int AVCProfileBaseline = 1;
                        public int profile;
                        public CodecProfileLevel(int profile) { this.profile = profile; }
                    }
                    public static final class CodecCapabilities {
                        public CodecProfileLevel[] profileLevels;
                        private final VideoCapabilities video;
                        public CodecCapabilities(VideoCapabilities video, CodecProfileLevel[] levels) {
                            this.video = video; this.profileLevels = levels;
                        }
                        public VideoCapabilities getVideoCapabilities() { return video; }
                    }
                    public static class VideoCapabilities {
                        private final int widthAlignment;
                        private final int heightAlignment;
                        private final int maxWidth;
                        private final int maxFps;
                        private final Range<Integer> bitrate;
                        public VideoCapabilities(int widthAlignment, int heightAlignment, int maxWidth, int maxFps,
                                                 int minBitrate, int maxBitrate) {
                            this.widthAlignment = widthAlignment; this.heightAlignment = heightAlignment;
                            this.maxWidth = maxWidth; this.maxFps = maxFps;
                            this.bitrate = new Range<Integer>(minBitrate, maxBitrate);
                        }
                        public int getWidthAlignment() { return widthAlignment; }
                        public int getHeightAlignment() { return heightAlignment; }
                        public Range<Integer> getBitrateRange() { return bitrate; }
                        public boolean isSizeSupported(int width, int height) {
                            return width <= maxWidth && width > 0 && height > 0;
                        }
                        public Range<Double> getSupportedFrameRatesFor(int width, int height) {
                            return new Range<Double>(1.0, (double) maxFps);
                        }
                    }
                }
            """,
            "android/media/MediaCodecList.java": """
                package android.media;
                public final class MediaCodecList {
                    public static final int ALL_CODECS = 1;
                    private static MediaCodecInfo[] codecInfos = new MediaCodecInfo[0];
                    private static RuntimeException failure;
                    public MediaCodecList(int kind) {}
                    public MediaCodecInfo[] getCodecInfos() {
                        if (failure != null) throw failure;
                        return codecInfos;
                    }
                    public static void setCodecInfos(MediaCodecInfo[] value) { codecInfos = value; failure = null; }
                    public static void setFailure(RuntimeException value) { failure = value; }
                }
            """,
        }
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            sources = []
            for name, content in stubs.items():
                path = directory / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(textwrap.dedent(content))
                sources.append(str(path))

            classes = directory / "classes"
            classes.mkdir()
            subprocess.run([
                "javac", "--release", "8", "-d", str(classes), *sources,
                str(ROOT / "android/app/src/main/java/com/vwww/mira/screen/AvcEncoderProfile.java"),
                str(ROOT / "android/app/src/main/java/com/vwww/mira/screen/AvcEncoderSelector.java"),
                str(ROOT / "tests/java/com/vwww/mira/screen/AvcEncoderSelectorTest.java"),
            ], check=True, capture_output=True, text=True)
            result = subprocess.run([
                "java", "-cp", str(classes), "com.vwww.mira.screen.AvcEncoderSelectorTest",
            ], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("PASS:", result.stdout)


if __name__ == "__main__":
    unittest.main()
