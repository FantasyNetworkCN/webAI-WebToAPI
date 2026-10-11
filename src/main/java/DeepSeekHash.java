import java.nio.charset.StandardCharsets;

/**
 * Pure-Java reimplementation of DeepSeek's {@code DeepSeekHashV1}, the hash used
 * by chat.deepseek.com's one-time proof-of-work anti-replay challenge.
 *
 * <p>It is a Keccak-f[1600] sponge with rate 136 bytes (capacity 512 bit) and a
 * 256-bit output, i.e. SHA3-256 with one twist: the permutation runs only 23
 * rounds using round constants {@code RC[1..23]} (RC[0] is skipped), which is
 * exactly "SHA3-256 with one fewer round". The 0x06 domain byte, the 0x80 final
 * byte and the little-endian lane packing all match standard SHA3-256.
 *
 * <p>Self-check vectors (byte-for-byte identical to the site's wasm module):
 * <pre>
 *   H("")    = e594808bc5b7151ac160c6d39a02e0a8e261ed588578403099e3561dc40c26b3
 *   H("abc") = f841106c601ce9be9bc38525e90d4178d47f21dd8eb9f238fc55ffaa4ca94506
 * </pre>
 */
final class DeepSeekHash {
    private static final int RATE = 136;
    private static final int ROUNDS = 23;

    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
            0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };

    private static final int[] ROTC = {
            1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
    };

    private static final int[] PILN = {
            10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
    };

    private DeepSeekHash() {
    }

    static byte[] hash(byte[] message) {
        long[] st = new long[25];
        int pt = 0;
        for (byte b : message) {
            st[pt >> 3] ^= (long) (b & 0xff) << (8 * (pt & 7));
            if (++pt == RATE) {
                permute(st);
                pt = 0;
            }
        }
        st[pt >> 3] ^= 0x06L << (8 * (pt & 7));
        st[(RATE - 1) >> 3] ^= 0x80L << (8 * ((RATE - 1) & 7));
        permute(st);

        byte[] out = new byte[32];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (st[i >> 3] >>> (8 * (i & 7)));
        }
        return out;
    }

    static String hex(String message) {
        return hex(message.getBytes(StandardCharsets.UTF_8));
    }

    static String hex(byte[] message) {
        byte[] digest = hash(message);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    /** Keccak-f[1600] permutation, 23 rounds starting at RC[1] (RC[0] is skipped). */
    private static void permute(long[] st) {
        long[] bc = new long[5];
        for (int round = 1; round <= ROUNDS; round++) {
            for (int i = 0; i < 5; i++) {
                bc[i] = st[i] ^ st[i + 5] ^ st[i + 10] ^ st[i + 15] ^ st[i + 20];
            }
            for (int i = 0; i < 5; i++) {
                long t = bc[(i + 4) % 5] ^ Long.rotateLeft(bc[(i + 1) % 5], 1);
                for (int j = 0; j < 25; j += 5) {
                    st[j + i] ^= t;
                }
            }

            long t = st[1];
            for (int i = 0; i < 24; i++) {
                int j = PILN[i];
                long tmp = st[j];
                st[j] = Long.rotateLeft(t, ROTC[i]);
                t = tmp;
            }

            for (int j = 0; j < 25; j += 5) {
                for (int i = 0; i < 5; i++) {
                    bc[i] = st[j + i];
                }
                for (int i = 0; i < 5; i++) {
                    st[j + i] = bc[i] ^ ((~bc[(i + 1) % 5]) & bc[(i + 2) % 5]);
                }
            }

            st[0] ^= RC[round];
        }
    }
}
