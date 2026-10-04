package java.lang;

public class Runtime$Version implements Comparable<Runtime$Version> {
    private final int[] v;

    public Runtime$Version(int... v) {
        this.v = (v != null) ? v.clone() : new int[0];
    }

    public int major() { return v.length > 0 ? v[0] : 0; }
    public int minor() { return v.length > 1 ? v[1] : 0; }
    public int patch() { return v.length > 2 ? v[2] : 0; }
    public int incremental() { return v.length > 3 ? v[3] : 0; }
    public int pre() { return -1; }

    public int compareTo(Runtime$Version other) {
        int len = Math.min(this.v.length, other.v.length);
        for (int i = 0; i < len; i++) {
            int c = Integer.compare(this.v[i], other.v[i]);
            if (c != 0) return c;
        }
        return Integer.compare(this.v.length, other.v.length);
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(v[i]);
        }
        return sb.toString();
    }
}
