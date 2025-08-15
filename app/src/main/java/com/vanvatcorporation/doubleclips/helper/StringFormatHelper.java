package com.vanvatcorporation.doubleclips.helper;

import java.math.RoundingMode;
import java.text.DecimalFormat;

public class StringFormatHelper {
    public static String smartRound(double value, int decimalCount, boolean hideDecimalWhenRounded) {
        double multiplier = Math.pow(10, decimalCount);
        double rounded = Math.round(value * multiplier) / multiplier;

        boolean isWholeNumber = rounded == Math.floor(rounded);
        if (isWholeNumber && hideDecimalWhenRounded) {
            return new DecimalFormat("0").format(rounded);
        }

        StringBuilder pattern = new StringBuilder("0.");
        for (int i = 0; i < decimalCount; i++) {
            pattern.append("0");
        }

        DecimalFormat df = new DecimalFormat(pattern.toString());
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(rounded);
    }
}
