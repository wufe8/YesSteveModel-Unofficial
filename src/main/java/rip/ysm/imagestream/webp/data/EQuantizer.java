package rip.ysm.imagestream.webp.data;

final class EQuantizer {
   private EQuantizer() {
   }

   static void quantizeY(int[] coeffs, int qp) {
      int factDC = LookUp.QDC[qp];
      int invFactAC = LookUp.QAC[qp];
      quantize(coeffs, factDC, invFactAC);
   }

   static void quantizeUV(int[] coeffs, int qp) {
      int factDC = LookUp.QDC[qp];
      if (factDC > 132) {
         factDC = 132;
      }

      int invFactAC = LookUp.QAC[qp];
      quantize(coeffs, factDC, invFactAC);
   }

   static void quantizeY2(int[] coeffs, int qp) {
      int factDC = LookUp.QDC[qp] << 1;
      int factAC = 155 * LookUp.QAC[qp] / 100;
      if (factAC < 8) {
         factAC = 8;
      }

      quantize(coeffs, factDC, factAC);
   }

   private static void quantize(int[] coeffs, int factDC, int factAC) {
      coeffs[0] /= factDC;

      for (int i = 1; i < 16; i++) {
         coeffs[i] /= factAC;
      }
   }

   static void dequantY(int[] coeffs, int qp) {
      int factDC = LookUp.QDC[qp];
      int factAC = LookUp.QAC[qp];
      dequant(coeffs, factDC, factAC);
   }

   static void dequantUV(int[] coeffs, int qp) {
      int factDC = LookUp.QDC[qp];
      if (factDC > 132) {
         factDC = 132;
      }

      int factAC = LookUp.QAC[qp];
      dequant(coeffs, factDC, factAC);
   }

   static void dequantY2(int[] coeffs, int qp) {
      int factDC = LookUp.QDC[qp] << 1;
      int factAC = 155 * LookUp.QAC[qp] / 100;
      if (factAC < 8) {
         factAC = 8;
      }

      dequant(coeffs, factDC, factAC);
   }

   private static void dequant(int[] coeffs, int factDC, int factAC) {
      coeffs[0] *= factDC;

      for (int i = 1; i < 16; i++) {
         coeffs[i] *= factAC;
      }
   }
}
