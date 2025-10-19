package android.util;

public class Log {
    public static int d(String tag, String msg) {
        System.out.println(tag + ": " + msg);
        return 0;
    }
    public static int i(String tag, String msg) {
        System.out.println(tag + ": " + msg);
        return 0;
    }
    public static int e(String tag, String msg) {
        System.err.println(tag + ": " + msg);
        return 0;
    }
    public static int w(String tag, String msg) {
        System.err.println(tag + ": " + msg);
        return 0;
    }
}

