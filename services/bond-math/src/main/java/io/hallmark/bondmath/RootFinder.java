package io.hallmark.bondmath;

import java.util.function.DoubleUnaryOperator;

public final class RootFinder {
    private static final int MAX_ITERATIONS = 200;
    private static final int MAX_EXPANSIONS = 60;

    private RootFinder() {}

    public static double solve(DoubleUnaryOperator f, double lo, double hi, double tolerance) {
        double a = lo;
        double b = hi;
        double fa = f.applyAsDouble(a);
        double fb = f.applyAsDouble(b);
        int expansions = 0;
        while (fa * fb > 0 || Double.isNaN(fa) || Double.isNaN(fb)) {
            if (++expansions > MAX_EXPANSIONS) {
                throw new NoConvergenceException("no sign change in [" + a + ", " + b + "]");
            }
            double width = b - a;
            if (Double.isNaN(fa) || Double.isNaN(fb)) {
                throw new NoConvergenceException("function undefined on [" + a + ", " + b + "]");
            }
            if (Math.abs(fa) < Math.abs(fb)) {
                a -= width;
                fa = f.applyAsDouble(a);
            } else {
                b += width;
                fb = f.applyAsDouble(b);
            }
        }
        return brent(f, a, b, fa, fb, tolerance);
    }

    private static double brent(DoubleUnaryOperator f, double a, double b, double fa, double fb, double tol) {
        if (fa == 0) {
            return a;
        }
        if (fb == 0) {
            return b;
        }
        double c = a;
        double fc = fa;
        double d = b - a;
        double e = d;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (fb * fc > 0) {
                c = a;
                fc = fa;
                d = b - a;
                e = d;
            }
            if (Math.abs(fc) < Math.abs(fb)) {
                a = b;
                b = c;
                c = a;
                fa = fb;
                fb = fc;
                fc = fa;
            }
            double tol1 = 2 * Math.ulp(b) + 0.5 * tol;
            double m = 0.5 * (c - b);
            if (Math.abs(m) <= tol1 || fb == 0) {
                return b;
            }
            if (Math.abs(e) >= tol1 && Math.abs(fa) > Math.abs(fb)) {
                double s = fb / fa;
                double p;
                double q;
                if (a == c) {
                    p = 2 * m * s;
                    q = 1 - s;
                } else {
                    double qa = fa / fc;
                    double r = fb / fc;
                    p = s * (2 * m * qa * (qa - r) - (b - a) * (r - 1));
                    q = (qa - 1) * (r - 1) * (s - 1);
                }
                if (p > 0) {
                    q = -q;
                } else {
                    p = -p;
                }
                if (2 * p < Math.min(3 * m * q - Math.abs(tol1 * q), Math.abs(e * q))) {
                    e = d;
                    d = p / q;
                } else {
                    d = m;
                    e = d;
                }
            } else {
                d = m;
                e = d;
            }
            a = b;
            fa = fb;
            b += Math.abs(d) > tol1 ? d : Math.copySign(tol1, m);
            fb = f.applyAsDouble(b);
        }
        throw new NoConvergenceException("Brent did not converge");
    }

    public static final class NoConvergenceException extends RuntimeException {
        public NoConvergenceException(String message) {
            super(message);
        }
    }
}
