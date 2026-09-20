package io.hallmark.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.hallmark.pricing.domain.RatingBuckets;
import io.hallmark.pricing.domain.RobustRegression;
import org.junit.jupiter.api.Test;

class RobustRegressionTest {
    @Test
    void recoversALineDespiteGrossOutliers() {
        int n = 40;
        double[][] x = new double[n][];
        double[] y = new double[n];
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            double t = Math.log1p(0.25 * i);
            x[i] = new double[] {1, t};
            y[i] = 60 + 25 * t + ((i % 2 == 0) ? 1.5 : -1.5);
            w[i] = 1;
        }

        for (int i = 0; i < 5; i++) {
            y[i * 7] += 400;
        }
        RobustRegression.Fit fit = RobustRegression.fit(x, y, w);
        assertThat(fit.coefficients()[0]).isCloseTo(60, within(3.0));
        assertThat(fit.coefficients()[1]).isCloseTo(25, within(3.0));
        assertThat(fit.predict(1, Math.log1p(5))).isCloseTo(60 + 25 * Math.log1p(5), within(4.0));
        assertThat(fit.effectiveN()).isBetween(30.0, 40.0);
    }

    @Test
    void rejectsUnderdeterminedDesigns() {
        assertThatThrownBy(() -> RobustRegression.fit(new double[][] {{1, 2}}, new double[] {1}, new double[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bucketsMapNotches() {
        assertThat(RatingBuckets.bucket(null)).isEqualTo(RatingBuckets.UNRATED);
        assertThat(RatingBuckets.bucket(1)).isEqualTo("AAA");
        assertThat(RatingBuckets.bucket(7)).isEqualTo("A-");
        assertThat(RatingBuckets.bucket(10)).isEqualTo("BBB");
        assertThat(RatingBuckets.bucket(14)).isEqualTo("SUB_IG");
        assertThat(RatingBuckets.representativeNotch(RatingBuckets.UNRATED)).isEqualTo(13);
    }
}
