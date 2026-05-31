package com.gdep;

public class FuzzyMatch {
    private static class Matrix {
        public int width;
        public int height;

        public int[] values;

        public Matrix(int width, int height) {
            this.width = width;
            this.height = height;

            this.values = new int[width * height];
        }

        public void setValue(int x, int y, int to) {
            values[x + y * this.width] = to;
        }

        public int getValue(int x, int y) {
            return values[x + y * this.width];
        }
    }

    public static int fuzzyMatch(String str, String sub) {
        // we need to delete every character of sub to be str
        if (str.isEmpty()) {
            return sub.length();
        }

        if (sub.isEmpty()) {
            return 0;
        }

        final int width = str.length() + 1;
        final int height = sub.length() + 1;

        Matrix matrix = new Matrix(width, height);

        for (int y = 1; y < height; y++) {
            matrix.setValue(0, y, y);
        }

        for (int y = 1; y < height; y++) {
            for (int x = 1; x < width; x++) {
                final char c = str.charAt(x - 1);
                final char subC = sub.charAt(y - 1);

                if (c == subC) {
                    matrix.setValue(x, y, matrix.getValue(x - 1, y - 1));
                } else {
                    // spotless:off
                    matrix.setValue(x, y, 1 + min3(
                        matrix.getValue(x, y - 1),
                        matrix.getValue(x - 1, y),
                        matrix.getValue(x - 1, y - 1))
                    );
                    // spotless:on
                }
            }
        }

        int minCol = 0;
        int minDist = Integer.MAX_VALUE;
        for (int x = 0; x < width; x++) {
            int dist = matrix.getValue(x, height - 1);
            if (dist < minDist) {
                minCol = x;
                minDist = dist;
            }
        }

        // there are no matching characters
        // so we need trim the sub to fit the str
        // then change every character of trimmed sub to fit the str
        // which is just the length of sub
        if (minCol == 0) {
            return sub.length();
        }

        int posX = minCol;
        int posY = height - 1;

        while (true) {
            int diag = matrix.getValue(posX - 1, posY - 1);
            int cur = matrix.getValue(posX, posY);

            if (diag == cur) {
                posX -= 1;
                posY -= 1;
            } else {
                int up = matrix.getValue(posX, posY - 1);
                int left = matrix.getValue(posX - 1, posY);

                int min = min3(up, left, diag);

                if (up == min) {
                    posY -= 1;
                } else if (diag == min) {
                    posX -= 1;
                    posY -= 1;
                } else { // left === min
                    posX -= 1;
                }
            }

            if (posX <= 0 || posY <= 0) {
                break;
            }
        }

        return minDist;
    }

    private static int min3(int a, int b, int c) {
        return Math.min(Math.min(a, b), c);
    }
}
