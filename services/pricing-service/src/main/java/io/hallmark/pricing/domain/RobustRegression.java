package io.hallmark.pricing.domain;

import java.util.Arrays;

public final class RobustRegression {
    static final double HUBER_K = 1.345;

    private static final int ITERATIONS = 20;
    private static final double RIDGE = 1e-8;

    private RobustRegression() {}

    public record Fit(double[] coefficients, double scale, double effectiveN) {
        public double predict(double... x) {
            double y = 0;
            for (int j = 0; j < coefficients.length; j++) {
                y += coefficients[j] * x[j];
            }
            return y;
        }
    }

    public static Fit fit(double[][] x, double[] y, double[] w) {
        int n = y.length;
        int p = x[0].length;
        if (n < p) {
            throw new IllegalArgumentException("need at least " + p + " observations, got " + n);
        }
        double[] robust = new double[n];
        Arrays.fill(robust, 1.0);
        double[] beta = solve(x, y, w, robust, p);
        double scale = 0;
        for (int iter = 0; iter < ITERATIONS; iter++) {
            double[] residuals = new double[n];
            for (int i = 0; i < n; i++) {
                residuals[i] = y[i] - dot(x[i], beta);
            }
            scale = mad(residuals) / 0.6745;
            if (scale < 1e-9) {
                break;
            }
            double threshold = HUBER_K * scale;
            for (int i = 0; i < n; i++) {
                double a = Math.abs(residuals[i]);
                robust[i] = a <= threshold ? 1.0 : threshold / a;
            }
            double[] next = solve(x, y, w, robust, p);
            double change = 0;
            for (int j = 0; j < p; j++) {
                change = Math.max(change, Math.abs(next[j] - beta[j]));
            }
            beta = next;
            if (change < 1e-8) {
                break;
            }
        }
        double sumW = 0;
        double sumW2 = 0;
        for (int i = 0; i < n; i++) {
            double wi = w[i] * robust[i];
            sumW += wi;
            sumW2 += wi * wi;
        }
        return new Fit(beta, scale, sumW2 == 0 ? 0 : sumW * sumW / sumW2);
    }

    private static double[] solve(double[][] x, double[] y, double[] w, double[] robust, int p) {
        double[][] a = new double[p][p + 1];
        for (int i = 0; i < y.length; i++) {
            double wi = w[i] * robust[i];
            for (int r = 0; r < p; r++) {
                for (int c = 0; c < p; c++) {
                    a[r][c] += wi * x[i][r] * x[i][c];
                }
                a[r][p] += wi * x[i][r] * y[i];
            }
        }
        for (int r = 0; r < p; r++) {
            a[r][r] += RIDGE;
        }

        for (int col = 0; col < p; col++) {
            int pivot = col;
            for (int r = col + 1; r < p; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            if (Math.abs(a[col][col]) < 1e-14) {
                throw new IllegalStateException("singular design matrix");
            }
            for (int r = 0; r < p; r++) {
                if (r != col) {
                    double f = a[r][col] / a[col][col];
                    for (int c = col; c <= p; c++) {
                        a[r][c] -= f * a[col][c];
                    }
                }
            }
        }
        double[] beta = new double[p];
        for (int r = 0; r < p; r++) {
            beta[r] = a[r][p] / a[r][r];
        }
        return beta;
    }

    static double mad(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double median = median(sorted);
        double[] dev = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            dev[i] = Math.abs(values[i] - median);
        }
        Arrays.sort(dev);
        return median(dev);
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
