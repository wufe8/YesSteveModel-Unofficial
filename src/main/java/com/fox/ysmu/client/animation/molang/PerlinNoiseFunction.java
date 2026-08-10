package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code ysm.perlin_noise(seed, x, y, z)}：返回 0-1 之间的 3D 柏林噪声浮点数。
 *
 * <p>OpenYSM 用 LWJGL3 的 {@code STBPerlin.stb_perlin_noise3_seed} 实现；
 * 1.7.10 是 LWJGL2 没有 STB，这里自实现 Ken Perlin improved noise（返回
 * {@code [-1,1]}）并映射到 {@code [0,1]}（与 YSM wiki 文档一致）。</p>
 *
 * <p>{@code seed} 决定噪声场：同一 seed 下相同坐标返回相同值，不同 seed
 * 产生不同场。内部按 seed 洗牌置换表并缓存（客户端渲染单线程，一个缓存即可）。</p>
 */
public class PerlinNoiseFunction extends Function {

    public PerlinNoiseFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 4;
    }

    @Override
    public double get() {
        int seed = (int) getArg(0);
        double x = getArg(1);
        double y = getArg(2);
        double z = getArg(3);
        return noise(seed, x, y, z);
    }

    /** 供控制器表达式路径复用（OpenYsmControllerExpressionEvaluator）。 */
    public static double noise(int seed, double x, double y, double z) {
        return PerlinNoise3D.noise(x, y, z, seed);
    }

    private static final class PerlinNoise3D {

        private static final int[] PERM = new int[512];
        private static int cachedSeed = Integer.MIN_VALUE;

        private static int[] permutation(int seed) {
            if (seed == cachedSeed) {
                return PERM;
            }
            int[] p = new int[256];
            for (int i = 0; i < 256; i++) {
                p[i] = i;
            }
            java.util.Random rnd = new java.util.Random(seed);
            for (int i = 255; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = p[i];
                p[i] = p[j];
                p[j] = t;
            }
            for (int i = 0; i < 512; i++) {
                PERM[i] = p[i & 255];
            }
            cachedSeed = seed;
            return PERM;
        }

        private static double noise(double x, double y, double z, int seed) {
            int[] perm = permutation(seed);
            int xi = (int) Math.floor(x) & 255;
            int yi = (int) Math.floor(y) & 255;
            int zi = (int) Math.floor(z) & 255;
            double xf = x - Math.floor(x);
            double yf = y - Math.floor(y);
            double zf = z - Math.floor(z);
            double u = fade(xf);
            double v = fade(yf);
            double w = fade(zf);

            int a = perm[xi] + yi;
            int aa = perm[a] + zi;
            int ab = perm[a + 1] + zi;
            int b = perm[xi + 1] + yi;
            int ba = perm[b] + zi;
            int bb = perm[b + 1] + zi;

            double n = lerp(w,
                lerp(v,
                    lerp(u, grad(perm[aa], xf, yf, zf), grad(perm[ba], xf - 1, yf, zf)),
                    lerp(u, grad(perm[ab], xf, yf - 1, zf), grad(perm[bb], xf - 1, yf - 1, zf))),
                lerp(v,
                    lerp(u, grad(perm[aa + 1], xf, yf, zf - 1), grad(perm[ba + 1], xf - 1, yf, zf - 1)),
                    lerp(u, grad(perm[ab + 1], xf, yf - 1, zf - 1), grad(perm[bb + 1], xf - 1, yf - 1, zf - 1))));
            // [-1,1] → [0,1]
            return 0.5d + 0.5d * n;
        }

        private static double fade(double t) {
            return t * t * t * (t * (t * 6.0d - 15.0d) + 10.0d);
        }

        private static double lerp(double t, double a, double b) {
            return a + t * (b - a);
        }

        private static double grad(int hash, double x, double y, double z) {
            switch (hash & 15) {
                case 0:  return x + y;
                case 1:  return -x + y;
                case 2:  return x - y;
                case 3:  return -x - y;
                case 4:  return x + z;
                case 5:  return -x + z;
                case 6:  return x - z;
                case 7:  return -x - z;
                case 8:  return y + z;
                case 9:  return -y + z;
                case 10: return y - z;
                case 11: return -y - z;
                case 12: return x + y;
                case 13: return -x + y;
                case 14: return -y + z;
                default: return -y - z;
            }
        }
    }
}
