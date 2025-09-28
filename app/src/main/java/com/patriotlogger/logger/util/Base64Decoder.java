package com.patriotlogger.logger.util;

import android.util.Base64;
import java.nio.charset.StandardCharsets;

public class Base64Decoder {
    public static String decode(String base64String) {
        byte[] decodedBytes = Base64.decode(base64String, Base64.DEFAULT);
        return new String(decodedBytes, StandardCharsets.UTF_8);
    }
}