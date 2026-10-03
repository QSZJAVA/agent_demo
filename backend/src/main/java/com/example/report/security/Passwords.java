package com.example.report.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 当前 Demo 的密码派生与摘要比对工具；只保存派生摘要，禁止把原始密码写入日志或文档。
 */
public final class Passwords {
    private Passwords() {}
    public static String hash(String password) {
        if(password==null || password.length()<12 || password.length()>256) throw new IllegalArgumentException("密码长度需为 12–256 位");
        byte[] salt=new byte[16]; new SecureRandom().nextBytes(salt);
        return "pbkdf2$600000$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derive(password,salt,600000));
    }
    public static boolean matches(String password,String encoded) {
        if(password==null || password.length()>256) return false;
        try {
            String[] parts=encoded.split("\\$");
            return parts.length==4 && parts[0].equals("pbkdf2") && Integer.parseInt(parts[1])==600000
                    && MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),derive(password,Base64.getDecoder().decode(parts[2]),600000));
        } catch(RuntimeException e) { return false; }
    }
    private static byte[] derive(String password,byte[] salt,int rounds) {
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,rounds,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch(Exception e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
}
