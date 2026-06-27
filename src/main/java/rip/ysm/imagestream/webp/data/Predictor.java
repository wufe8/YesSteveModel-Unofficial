package rip.ysm.imagestream.webp.data;

import java.awt.image.Raster;
import java.awt.image.WritableRaster;

class Predictor implements WTransform {
   private static final int BLACK = 0;
   private static final int L = 1;
   private static final int T = 2;
   private static final int TR = 3;
   private static final int TL = 4;
   private static final int AVERAGE_L_TR_T = 5;
   private static final int AVERAGE_L_TL = 6;
   private static final int AVERAGE_L_T = 7;
   private static final int AVERAGE_TL_T = 8;
   private static final int AVERAGE_T_TR = 9;
   private static final int AVERAGE_L_TL_T_TR = 10;
   private static final int SELECT = 11;
   private static final int CLAMP_ADD_SUB_FULL = 12;
   private static final int CLAMP_ADD_SUB_HALF = 13;
   private final Raster data;
   private final byte bits;

   public Predictor(Raster raster, byte bits) {
      this.data = raster;
      this.bits = bits;
   }

   @Override
   public void perform(WritableRaster raster) {
      int width = raster.getWidth();
      int height = raster.getHeight();
      byte[] rgba = new byte[4];
      raster.getDataElements(0, 0, rgba);
      rgba[3] = (byte)(rgba[3] + 255);
      raster.setDataElements(0, 0, rgba);
      byte[] predictor = new byte[4];
      byte[] predictor2 = new byte[4];
      byte[] predictor3 = new byte[4];

      for (int x = 1; x < width; x++) {
         raster.getDataElements(x, 0, rgba);
         raster.getDataElements(x - 1, 0, predictor);
         addPixels(rgba, predictor);
         raster.setDataElements(x, 0, rgba);
      }

      for (int y = 1; y < height; y++) {
         raster.getDataElements(0, y, rgba);
         raster.getDataElements(0, y - 1, predictor);
         addPixels(rgba, predictor);
         raster.setDataElements(0, y, rgba);
      }

      for (int y = 1; y < height; y++) {
         for (int x = 1; x < width; x++) {
            int transformType = this.data.getSample(x >> this.bits, y >> this.bits, 1);
            raster.getDataElements(x, y, rgba);
            int lX = x - 1;
            int tY = y - 1;
            int trX = x == width - 1 ? 0 : x + 1;
            int trY = x == width - 1 ? y : tY;
            switch (transformType) {
               case 0:
                  rgba[3] = (byte)(rgba[3] + 255);
                  break;
               case 1:
                  raster.getDataElements(lX, y, predictor);
                  addPixels(rgba, predictor);
                  break;
               case 2:
                  raster.getDataElements(x, tY, predictor);
                  addPixels(rgba, predictor);
                  break;
               case 3:
                  raster.getDataElements(trX, trY, predictor);
                  addPixels(rgba, predictor);
                  break;
               case 4:
                  raster.getDataElements(lX, tY, predictor);
                  addPixels(rgba, predictor);
                  break;
               case 5:
                  raster.getDataElements(lX, y, predictor);
                  raster.getDataElements(trX, trY, predictor2);
                  average2(predictor, predictor2);
                  raster.getDataElements(x, tY, predictor2);
                  average2(predictor, predictor2);
                  addPixels(rgba, predictor);
                  break;
               case 6:
                  raster.getDataElements(lX, y, predictor);
                  raster.getDataElements(lX, tY, predictor2);
                  average2(predictor, predictor2);
                  addPixels(rgba, predictor);
                  break;
               case 7:
                  raster.getDataElements(lX, y, predictor);
                  raster.getDataElements(x, tY, predictor2);
                  average2(predictor, predictor2);
                  addPixels(rgba, predictor);
                  break;
               case 8:
                  raster.getDataElements(lX, tY, predictor);
                  raster.getDataElements(x, tY, predictor2);
                  average2(predictor, predictor2);
                  addPixels(rgba, predictor);
                  break;
               case 9:
                  raster.getDataElements(x, tY, predictor);
                  raster.getDataElements(trX, trY, predictor2);
                  average2(predictor, predictor2);
                  addPixels(rgba, predictor);
                  break;
               case 10:
                  raster.getDataElements(lX, y, predictor);
                  raster.getDataElements(lX, tY, predictor2);
                  average2(predictor, predictor2);
                  raster.getDataElements(x, tY, predictor2);
                  raster.getDataElements(trX, trY, predictor3);
                  average2(predictor2, predictor3);
                  average2(predictor, predictor2);
                  addPixels(rgba, predictor);
                  break;
               case 11:
                  raster.getDataElements(lX, y, predictor);
                  raster.getDataElements(x, tY, predictor2);
                  raster.getDataElements(lX, tY, predictor3);
                  addPixels(rgba, select(predictor, predictor2, predictor3));
                  break;
               case 12:
                  raster.getDataElements(lX, y, predictor);
                  raster.getDataElements(x, tY, predictor2);
                  raster.getDataElements(lX, tY, predictor3);
                  clampAddSubtractFull(predictor, predictor2, predictor3);
                  addPixels(rgba, predictor);
                  break;
               case 13:
                  raster.getDataElements(lX, y, predictor);
                  raster.getDataElements(x, tY, predictor2);
                  average2(predictor, predictor2);
                  raster.getDataElements(lX, tY, predictor2);
                  clampAddSubtractHalf(predictor, predictor2);
                  addPixels(rgba, predictor);
            }

            raster.setDataElements(x, y, rgba);
         }
      }
   }

   private static byte[] select(byte[] l, byte[] t, byte[] tl) {
      int pAlpha = addSubtractFull(l[3], t[3], tl[3]);
      int pRed = addSubtractFull(l[0], t[0], tl[0]);
      int pGreen = addSubtractFull(l[1], t[1], tl[1]);
      int pBlue = addSubtractFull(l[2], t[2], tl[2]);
      int pL = manhattanDistance(l, pAlpha, pRed, pGreen, pBlue);
      int pT = manhattanDistance(t, pAlpha, pRed, pGreen, pBlue);
      return pL < pT ? l : t;
   }

   private static int manhattanDistance(byte[] rgba, int pAlpha, int pRed, int pGreen, int pBlue) {
      return Math.abs(pAlpha - (rgba[3] & 0xFF)) + Math.abs(pRed - (rgba[0] & 0xFF)) + Math.abs(pGreen - (rgba[1] & 0xFF)) + Math.abs(pBlue - (rgba[2] & 0xFF));
   }

   private static void average2(byte[] rgba1, byte[] rgba2) {
      rgba1[0] = (byte)(((rgba1[0] & 255) + (rgba2[0] & 255)) / 2);
      rgba1[1] = (byte)(((rgba1[1] & 255) + (rgba2[1] & 255)) / 2);
      rgba1[2] = (byte)(((rgba1[2] & 255) + (rgba2[2] & 255)) / 2);
      rgba1[3] = (byte)(((rgba1[3] & 255) + (rgba2[3] & 255)) / 2);
   }

   private static int clamp(int a) {
      return Math.max(0, Math.min(a, 255));
   }

   private static void clampAddSubtractFull(byte[] a, byte[] b, byte[] c) {
      a[0] = (byte)clamp(addSubtractFull(a[0], b[0], c[0]));
      a[1] = (byte)clamp(addSubtractFull(a[1], b[1], c[1]));
      a[2] = (byte)clamp(addSubtractFull(a[2], b[2], c[2]));
      a[3] = (byte)clamp(addSubtractFull(a[3], b[3], c[3]));
   }

   private static void clampAddSubtractHalf(byte[] a, byte[] b) {
      a[0] = (byte)clamp(addSubtractHalf(a[0], b[0]));
      a[1] = (byte)clamp(addSubtractHalf(a[1], b[1]));
      a[2] = (byte)clamp(addSubtractHalf(a[2], b[2]));
      a[3] = (byte)clamp(addSubtractHalf(a[3], b[3]));
   }

   private static int addSubtractFull(byte a, byte b, byte c) {
      return (a & 0xFF) + (b & 0xFF) - (c & 0xFF);
   }

   private static int addSubtractHalf(byte a, byte b) {
      return (a & 0xFF) + ((a & 0xFF) - (b & 0xFF)) / 2;
   }

   private static void addPixels(byte[] rgba, byte[] predictor) {
      rgba[0] += predictor[0];
      rgba[1] += predictor[1];
      rgba[2] += predictor[2];
      rgba[3] += predictor[3];
   }
}
