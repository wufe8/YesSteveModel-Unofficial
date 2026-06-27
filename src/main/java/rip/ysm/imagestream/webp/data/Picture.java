package rip.ysm.imagestream.webp.data;

class Picture {
   private final WebpYUV color;
   final int width;
   final int height;
   public final byte[][] data;
   private static final int MAX_PLANES = 3;

   Picture(int width, int height, byte[][] data, WebpYUV color) {
      this.width = width;
      this.height = height;
      this.data = data;
      this.color = color;
   }

   static Picture create() {
      WebpYUV colorSpace = WebpYUV.YUV420;
      int width = 16;
      int height = 16;
      int[] planeSizes = new int[3];

      for (int i = 0; i < colorSpace.nComp; i++) {
         planeSizes[colorSpace.compPlane[i]] = planeSizes[colorSpace.compPlane[i]] + (16 >> colorSpace.compWidth[i]) * (16 >> colorSpace.compHeight[i]);
      }

      int nPlanes = 0;

      for (int i = 0; i < 3; i++) {
         nPlanes += planeSizes[i] != 0 ? 1 : 0;
      }

      byte[][] data = new byte[nPlanes][];
      int i = 0;

      for (int plane = 0; i < 3; i++) {
         if (planeSizes[i] != 0) {
            data[plane++] = new byte[planeSizes[i]];
         }
      }

      return new Picture(16, 16, data, colorSpace);
   }

   byte[] getPlaneData(int plane) {
      return this.data[plane];
   }

   final int getPlaneWidth(int plane) {
      return this.width >> this.color.compWidth[plane];
   }

   final int getPlaneHeight(int plane) {
      return this.height >> this.color.compHeight[plane];
   }
}
