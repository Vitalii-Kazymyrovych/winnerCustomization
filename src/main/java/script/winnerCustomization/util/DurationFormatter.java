package script.winnerCustomization.util;

import java.time.Duration;

public final class DurationFormatter {
    private DurationFormatter() {
    }

    public static String human(Duration duration) {
        if (duration == null) {
            return "";
        }
        long total = duration.getSeconds();
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return "%02d:%02d:%02d".formatted(h, m, s);
    }
}
