package com.example.myime;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 备份文件的加密。纯 JDK 代码(不依赖 Android),可以脱离手机单独测试;全部用系统自带的标准算法,没有自己发明的密码学。
 *
 * 密钥:密码 --PBKDF2-HMAC-SHA256(随机盐,默认 60 万次)--> 主密钥(32 字节);
 *       每个文件再用 HMAC-SHA256(主密钥, "myime-bk1" + 该文件的随机盐) 得到只用于这一个文件的加密密钥。
 *       PBKDF2 自己实现(只用 HmacSHA256),不依赖系统提供者,保证换一台手机、换一个系统版本结果也完全一样。
 *       密码先做 Unicode NFKC 规范化再转 UTF-8,输入法不同造成的"看起来一样"的字符不会算成不同的密码。
 * 加密:AES-256-GCM,每 64KB 一块,各块独立认证。
 *       块序号编进 nonce,"是不是最后一块"和整个文件头一起进认证数据 ——
 *       所以改一个字节、调换块的顺序、删掉末尾几块,解密时都会被发现(不会悄悄还原出残缺的数据)。
 *
 * 文件格式(全部大端):
 *   头 45 字节:"MYBK" | 版本(1) | PBKDF2 盐(16) | 迭代次数(4) | 文件盐(16) | 块大小(4)
 *   然后若干块:密文长度(4) | 密文+认证标签(16)
 */
public final class BackupCrypto {
    public static final int ITERATIONS = 600_000;
    private static final int MIN_ITER = 100_000;
    private static final int MAX_ITER = 5_000_000;   // 防止被构造的文件拖死手机
    private static final byte[] MAGIC = {'M', 'Y', 'B', 'K'};
    private static final int VERSION = 1;
    private static final int CHUNK = 64 * 1024;
    private static final int TAG = 16;
    private static final int HEADER_LEN = 4 + 1 + 16 + 4 + 16 + 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {
    }

    /** 解密认证失败:密码不对,或者文件被改过 / 损坏(两者没法区分)。 */
    public static final class BadPasswordException extends IOException {
        public BadPasswordException() {
            super("密码不对,或者备份文件已损坏");
        }
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    public static byte[] newSalt() {
        return randomBytes(16);
    }

    // ------------------------------------------------------------------ 密钥

    /** 密码 → 主密钥(32 字节)。要跑几秒钟,不要在主线程调用。 */
    public static byte[] deriveMaster(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        byte[] pw = Normalizer.normalize(new String(password), Normalizer.Form.NFKC).getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pw, "HmacSHA256"));
            mac.update(salt);
            mac.update(new byte[]{0, 0, 0, 1});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                mac.update(u, 0, u.length);
                try {
                    mac.doFinal(u, 0);
                } catch (javax.crypto.ShortBufferException e) {
                    throw new GeneralSecurityException(e);
                }
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            return t;
        } finally {
            Arrays.fill(pw, (byte) 0);
        }
    }

    private static SecretKeySpec fileKey(byte[] master, byte[] fileSalt) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(master, "HmacSHA256"));
        mac.update("myime-bk1".getBytes(StandardCharsets.US_ASCII));
        mac.update(fileSalt);
        return new SecretKeySpec(mac.doFinal(), "AES");
    }

    private static byte[] nonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[11 - i] = (byte) (counter >>> (8 * i));
        return n;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    // ------------------------------------------------------------------ 加密

    /**
     * 往 out 写一个加密文件:返回的流里写明文,close() 时写完最后一块并关闭 out。
     * pbkdfSalt / iterations 要和算出 master 时用的一致(写进文件头,换手机还原时用)。
     */
    public static OutputStream encrypt(OutputStream out, byte[] master, byte[] pbkdfSalt, int iterations) throws IOException {
        try {
            byte[] fileSalt = randomBytes(16);
            byte[] header = new byte[HEADER_LEN];
            System.arraycopy(MAGIC, 0, header, 0, 4);
            header[4] = VERSION;
            System.arraycopy(pbkdfSalt, 0, header, 5, 16);
            putInt(header, 21, iterations);
            System.arraycopy(fileSalt, 0, header, 25, 16);
            putInt(header, 41, CHUNK);
            out.write(header);
            return new EncStream(out, fileKey(master, fileSalt), header);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static final class EncStream extends OutputStream {
        private final OutputStream out;
        private final SecretKeySpec key;
        private final byte[] header;
        private final byte[] buf = new byte[CHUNK];
        private int n = 0;
        private long counter = 0;
        private boolean closed = false;

        EncStream(OutputStream out, SecretKeySpec key, byte[] header) {
            this.out = out;
            this.key = key;
            this.header = header;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("closed");
            while (len > 0) {
                if (n == CHUNK) emit(false);   // 满了而且还有数据来,所以这一块一定不是最后一块
                int k = Math.min(len, CHUNK - n);
                System.arraycopy(b, off, buf, n, k);
                n += k;
                off += k;
                len -= k;
            }
        }

        private void emit(boolean last) throws IOException {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG * 8, nonce(counter)));
                c.updateAAD(header);
                c.updateAAD(new byte[]{(byte) (last ? 1 : 0)});
                byte[] ct = c.doFinal(buf, 0, n);
                byte[] len = new byte[4];
                putInt(len, 0, ct.length);
                out.write(len);
                out.write(ct);
                counter++;
                n = 0;
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                emit(true);
                out.flush();
            } finally {
                out.close();
            }
        }
    }

    // ------------------------------------------------------------------ 解密

    /** 用密码解密:从文件头读盐和迭代次数,现算主密钥(要几秒)。 */
    public static InputStream decrypt(InputStream in, char[] password) throws IOException {
        byte[] header = readHeader(in);
        int iter = getInt(header, 21);
        byte[] salt = Arrays.copyOfRange(header, 5, 21);
        try {
            return open(in, header, deriveMaster(password, salt, iter));
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** 用已经算好的主密钥解密(自动备份后的校验读回用,不用再等几秒)。 */
    public static InputStream decryptWithMaster(InputStream in, byte[] master) throws IOException {
        return open(in, readHeader(in), master);
    }

    private static byte[] readHeader(InputStream in) throws IOException {
        byte[] header = new byte[HEADER_LEN];
        readFully(in, header, 0, HEADER_LEN);
        for (int i = 0; i < 4; i++) {
            if (header[i] != MAGIC[i]) throw new IOException("不是本输入法的备份文件");
        }
        if (header[4] != VERSION) throw new IOException("备份文件版本太新,请先更新输入法");
        int iter = getInt(header, 21);
        if (iter < MIN_ITER || iter > MAX_ITER) throw new IOException("备份文件头不正常");
        if (getInt(header, 41) != CHUNK) throw new IOException("备份文件头不正常");
        return header;
    }

    private static InputStream open(InputStream in, byte[] header, byte[] master) throws IOException {
        try {
            byte[] fileSalt = Arrays.copyOfRange(header, 25, 41);
            return new DecStream(in, fileKey(master, fileSalt), header);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int r = in.read(b, off, len);
            if (r < 0) throw new EOFException("备份文件不完整");
            off += r;
            len -= r;
        }
    }

    /** 读 4 字节长度;一个字节都没有(正常结束)返回 -1;读到一半断了抛异常。 */
    private static int readLenOrEnd(InputStream in) throws IOException {
        byte[] b = new byte[4];
        int r = in.read(b, 0, 1);
        if (r < 0) return -1;
        readFully(in, b, 1, 3);
        return getInt(b, 0);
    }

    private static final class DecStream extends InputStream {
        private final InputStream in;
        private final SecretKeySpec key;
        private final byte[] header;
        private byte[] plain = new byte[0];
        private int pos = 0;
        private long counter = 0;
        private int nextLen;
        private boolean done = false;

        DecStream(InputStream in, SecretKeySpec key, byte[] header) throws IOException {
            this.in = in;
            this.key = key;
            this.header = header;
            this.nextLen = readLenOrEnd(in);
            if (nextLen < 0) throw new EOFException("备份文件不完整");
        }

        private boolean fill() throws IOException {
            while (pos >= plain.length) {
                if (done) return false;
                if (nextLen < TAG || nextLen > CHUNK + TAG) throw new BadPasswordException();
                byte[] ct = new byte[nextLen];
                readFully(in, ct, 0, nextLen);
                int following = readLenOrEnd(in);
                boolean last = following < 0;
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG * 8, nonce(counter)));
                    c.updateAAD(header);
                    c.updateAAD(new byte[]{(byte) (last ? 1 : 0)});
                    plain = c.doFinal(ct);
                } catch (AEADBadTagException e) {
                    throw new BadPasswordException();
                } catch (GeneralSecurityException e) {
                    throw new BadPasswordException();
                }
                pos = 0;
                counter++;
                nextLen = following;
                if (last) done = true;
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!fill()) return -1;
            return plain[pos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int k = Math.min(len, plain.length - pos);
            System.arraycopy(plain, pos, b, off, k);
            pos += k;
            return k;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
