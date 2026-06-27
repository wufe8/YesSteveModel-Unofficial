package rip.ysm.imagestream.webp.data;

final class Util {
   private Util() {
   }

   static int clip(int val) {
      return val < -128 ? -128 : Math.min(val, 127);
   }

   static int sumByte(byte[] array) {
      int result = 0;

      for (byte b : array) {
         result += b;
      }

      return result;
   }

   static int sumByte3(byte[] array, int from) {
      int result = 0;

      for (int i = from; i < from + 16; i++) {
         result += array[i];
      }

      return result;
   }
}
