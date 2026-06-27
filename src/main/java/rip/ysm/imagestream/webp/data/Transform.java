package rip.ysm.imagestream.webp.data;

final class Transform {
   private Transform() {
   }

   static int[][] cosine(int[] input, int[] output) {
      int offset = 0;

      for (int i = 0; i < 4; i++) {
         int i0 = input[offset];
         int i4 = input[offset + 4];
         int i8 = input[offset + 8];
         int i12 = input[offset + 12];
         int a1 = i0 + i8;
         int b1 = i0 - i8;
         int t1 = i4 * 35468 >> 16;
         int t2 = i12 + (i12 * 20091 >> 16);
         int c1 = t1 - t2;
         t1 = i4 + (i4 * 20091 >> 16);
         t2 = i12 * 35468 >> 16;
         int d1 = t1 + t2;
         output[offset] = a1 + d1;
         output[offset + 12] = a1 - d1;
         output[offset + 4] = b1 + c1;
         output[offset + 8] = b1 - c1;
         offset++;
      }

      int diffo = 0;
      int[][] diff = new int[4][4];
      offset = 0;

      for (int var17 = 0; var17 < 4; var17++) {
         int o4 = offset * 4;
         int a1 = output[o4] + output[o4 + 2];
         int b1 = output[o4] - output[o4 + 2];
         int t1 = output[o4 + 1] * 35468 >> 16;
         int t2 = output[o4 + 3] + (output[o4 + 3] * 20091 >> 16);
         int c1 = t1 - t2;
         t1 = output[o4 + 1] + (output[o4 + 1] * 20091 >> 16);
         t2 = output[o4 + 3] * 35468 >> 16;
         int d1 = t1 + t2;
         output[o4] = a1 + d1 + 4 >> 3;
         output[o4 + 3] = a1 - d1 + 4 >> 3;
         output[o4 + 1] = b1 + c1 + 4 >> 3;
         output[o4 + 2] = b1 - c1 + 4 >> 3;
         diff[0][diffo] = a1 + d1 + 4 >> 3;
         diff[3][diffo] = a1 - d1 + 4 >> 3;
         diff[1][diffo] = b1 + c1 + 4 >> 3;
         diff[2][diffo] = b1 - c1 + 4 >> 3;
         offset++;
         diffo++;
      }

      return diff;
   }

   static int[][] walsh(int[] input, int[] output) {
      int[][] diff = new int[4][4];
      int offset = 0;

      for (int i = 0; i < 4; i++) {
         int a1 = input[offset] + input[offset + 12];
         int b1 = input[offset + 4] + input[offset + 8];
         int c1 = input[offset + 4] - input[offset + 8];
         int d1 = input[offset] - input[offset + 12];
         output[offset] = a1 + b1;
         output[offset + 4] = c1 + d1;
         output[offset + 8] = a1 - b1;
         output[offset + 12] = d1 - c1;
         offset++;
      }

      int var18 = 0;

      for (int var13 = 0; var13 < 4; var13++) {
         int a1 = output[var18] + output[var18 + 3];
         int b1 = output[var18 + 1] + output[var18 + 2];
         int c1 = output[var18 + 1] - output[var18 + 2];
         int d1 = output[var18] - output[var18 + 3];
         int a2 = a1 + b1;
         int b2 = c1 + d1;
         int c2 = a1 - b1;
         int d2 = d1 - c1;
         output[var18] = a2 + 3 >> 3;
         output[var18 + 1] = b2 + 3 >> 3;
         output[var18 + 2] = c2 + 3 >> 3;
         output[var18 + 3] = d2 + 3 >> 3;
         diff[0][var13] = a2 + 3 >> 3;
         diff[1][var13] = b2 + 3 >> 3;
         diff[2][var13] = c2 + 3 >> 3;
         diff[3][var13] = d2 + 3 >> 3;
         var18 += 4;
      }

      return diff;
   }

   static void fdct4x4(int[] coef) {
      for (int i = 0; i < 16; i += 4) {
         int a1 = coef[i] + coef[i + 3] << 3;
         int b1 = coef[i + 1] + coef[i + 2] << 3;
         int c1 = coef[i + 1] - coef[i + 2] << 3;
         int d1 = coef[i] - coef[i + 3] << 3;
         coef[i] = a1 + b1;
         coef[i + 2] = a1 - b1;
         coef[i + 1] = c1 * 2217 + d1 * 5352 + 14500 >> 12;
         coef[i + 3] = d1 * 2217 - c1 * 5352 + 7500 >> 12;
      }

      for (int i = 0; i < 4; i++) {
         int a1 = coef[i] + coef[i + 12];
         int b1 = coef[i + 4] + coef[i + 8];
         int c1 = coef[i + 4] - coef[i + 8];
         int d1 = coef[i] - coef[i + 12];
         coef[i] = a1 + b1 + 7 >> 4;
         coef[i + 8] = a1 - b1 + 7 >> 4;
         coef[i + 4] = (c1 * 2217 + d1 * 5352 + 12000 >> 16) + (d1 != 0 ? 1 : 0);
         coef[i + 12] = d1 * 2217 - c1 * 5352 + 51000 >> 16;
      }
   }

   static void walsh4x4(int[] coef) {
      for (int i = 0; i < 16; i += 4) {
         int a1 = coef[i] + coef[i + 2] << 2;
         int d1 = coef[i + 1] + coef[i + 3] << 2;
         int c1 = coef[i + 1] - coef[i + 3] << 2;
         int b1 = coef[i] - coef[i + 2] << 2;
         coef[i] = a1 + d1 + (a1 != 0 ? 1 : 0);
         coef[i + 1] = b1 + c1;
         coef[i + 2] = b1 - c1;
         coef[i + 3] = a1 - d1;
      }

      for (int i = 0; i < 4; i++) {
         int a1 = coef[i] + coef[i + 8];
         int d1 = coef[i + 4] + coef[i + 12];
         int c1 = coef[i + 4] - coef[i + 12];
         int b1 = coef[i] - coef[i + 8];
         int a2 = a1 + d1;
         int b2 = b1 + c1;
         int c2 = b1 - c1;
         int d2 = a1 - d1;
         a2 += a2 < 0 ? 1 : 0;
         b2 += b2 < 0 ? 1 : 0;
         c2 += c2 < 0 ? 1 : 0;
         d2 += d2 < 0 ? 1 : 0;
         coef[i] = a2 + 3 >> 3;
         coef[i + 4] = b2 + 3 >> 3;
         coef[i + 8] = c2 + 3 >> 3;
         coef[i + 12] = d2 + 3 >> 3;
      }
   }

   static void idct4x4(int[] coef) {
      for (int i = 0; i < 4; i++) {
         int a1 = coef[i] + coef[i + 8];
         int b1 = coef[i] - coef[i + 8];
         int temp1 = coef[i + 4] * 35468 >> 16;
         int temp2 = coef[i + 12] + (coef[i + 12] * 20091 >> 16);
         int c1 = temp1 - temp2;
         temp1 = coef[i + 4] + (coef[i + 4] * 20091 >> 16);
         temp2 = coef[i + 12] * 35468 >> 16;
         int d1 = temp1 + temp2;
         coef[i] = a1 + d1;
         coef[i + 12] = a1 - d1;
         coef[i + 4] = b1 + c1;
         coef[i + 8] = b1 - c1;
      }

      for (int i = 0; i < 16; i += 4) {
         int a1 = coef[i] + coef[i + 2];
         int b1 = coef[i] - coef[i + 2];
         int temp1 = coef[i + 1] * 35468 >> 16;
         int temp2 = coef[i + 3] + (coef[i + 3] * 20091 >> 16);
         int c1 = temp1 - temp2;
         temp1 = coef[i + 1] + (coef[i + 1] * 20091 >> 16);
         temp2 = coef[i + 3] * 35468 >> 16;
         int d1 = temp1 + temp2;
         coef[i] = a1 + d1 + 4 >> 3;
         coef[i + 3] = a1 - d1 + 4 >> 3;
         coef[i + 1] = b1 + c1 + 4 >> 3;
         coef[i + 2] = b1 - c1 + 4 >> 3;
      }
   }

   static void iwalsh4x4(int[] coef) {
      for (int i = 0; i < 4; i++) {
         int a1 = coef[i] + coef[i + 12];
         int b1 = coef[i + 4] + coef[i + 8];
         int c1 = coef[i + 4] - coef[i + 8];
         int d1 = coef[i] - coef[i + 12];
         coef[i] = a1 + b1;
         coef[i + 4] = c1 + d1;
         coef[i + 8] = a1 - b1;
         coef[i + 12] = d1 - c1;
      }

      for (int i = 0; i < 16; i += 4) {
         int a1 = coef[i] + coef[i + 3];
         int b1 = coef[i + 1] + coef[i + 2];
         int c1 = coef[i + 1] - coef[i + 2];
         int d1 = coef[i] - coef[i + 3];
         int a2 = a1 + b1;
         int b2 = c1 + d1;
         int c2 = a1 - b1;
         int d2 = d1 - c1;
         coef[i] = a2 + 3 >> 3;
         coef[i + 1] = b2 + 3 >> 3;
         coef[i + 2] = c2 + 3 >> 3;
         coef[i + 3] = d2 + 3 >> 3;
      }
   }
}
