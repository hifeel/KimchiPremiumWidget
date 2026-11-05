package com.kimchi.widget;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * 가격대에 맞춰 자릿수를 정하는 포맷터.
 * 1 미만 값은 유효숫자 4자리로 표시해 SHIB 같은 저가 코인이 ₩0으로 보이지 않게 한다.
 */
final class PriceFormat {

    private static final MathContext SMALL_VALUE_PRECISION = new MathContext(4, RoundingMode.HALF_UP);

    private PriceFormat() {
    }

    /** 100 이상은 정수, 1 이상은 소수 둘째 자리까지, 1 미만은 유효숫자 4자리. */
    static String krw(double value) {
        double abs = Math.abs(value);
        if (abs >= 100) return new DecimalFormat("#,##0").format(value);
        if (abs >= 1) return new DecimalFormat("#,##0.##").format(value);
        return formatSmall(value);
    }

    /** 1 이상은 소수 둘째 자리, 1 미만은 유효숫자 4자리. */
    static String usd(double value) {
        if (Math.abs(value) >= 1) return new DecimalFormat("#,##0.00").format(value);
        return formatSmall(value);
    }

    private static String formatSmall(double value) {
        if (Double.isNaN(value)) return "--";
        if (value == 0) return "0";
        return new BigDecimal(value, SMALL_VALUE_PRECISION).stripTrailingZeros().toPlainString();
    }
}
